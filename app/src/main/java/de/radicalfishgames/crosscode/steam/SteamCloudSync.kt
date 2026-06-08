package de.radicalfishgames.crosscode.steam

import android.util.Log
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStatesCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// Logs in with a stored refresh token and syncs Steam Cloud files for CrossCode (app 368340).
// PULL = download a cloud file to disk (the file CloudSaveFeature injects into localStorage before boot).
// Steam serves cloud files via a one-shot HTTPS URL; large files come back zipped.
class SteamCloudSync(private val accountName: String, private val refreshToken: String) {

    private val client = SteamClient()
    private val manager = CallbackManager(client)
    private val steamUser: SteamUser = client.getHandler(SteamUser::class.java)!!
    private val cloud: SteamCloud = client.getHandler(SteamCloud::class.java)!!

    @Volatile private var pumping = false
    private var pumpThread: Thread? = null

    fun connect(timeoutSec: Long = 20): Boolean {
        val loggedOn = CountDownLatch(1)
        var result: EResult? = null

        manager.subscribe(ConnectedCallback::class.java) {
            steamUser.logOn(LogOnDetails().apply {
                username = accountName
                accessToken = refreshToken
                shouldRememberPassword = true
            })
        }
        manager.subscribe(LoggedOnCallback::class.java) { cb ->
            result = cb.result
            loggedOn.countDown()
        }

        startPump()
        client.connect()
        if (!loggedOn.await(timeoutSec, TimeUnit.SECONDS)) {
            Log.e("CrossCode", "[Steam] logon timed out")
            return false
        }
        Log.i("CrossCode", "[Steam] logon result: $result")
        return result == EResult.OK
    }

    data class Profile(val personaName: String?, val avatarHashHex: String?)

    // After logon, fetch the local user's display name + avatar hash. We go Online briefly and wait
    // for our own PersonaStatesCallback (the avatar hash builds the avatars.steamstatic.com URL).
    fun fetchProfile(timeoutSec: Long = 8): Profile? {
        val friends = client.getHandler(SteamFriends::class.java) ?: return null
        val ready = CountDownLatch(1)
        var name: String? = null
        var hashHex: String? = null
        manager.subscribe(PersonaStatesCallback::class.java) { cb ->
            if (friends.isLocalUser(cb.friendID)) {
                name = cb.name
                hashHex = cb.avatarHash?.toHex()
                ready.countDown()
            }
        }
        friends.setPersonaState(EPersonaState.Online)
        try { client.steamID?.let { friends.requestFriendInfo(it) } } catch (_: Throwable) {}
        ready.await(timeoutSec, TimeUnit.SECONDS)
        val finalName = name ?: runCatching { friends.getPersonaName() }.getOrNull()
        val finalHash = hashHex ?: runCatching { friends.getPersonaAvatar()?.toHex() }.getOrNull()
        Log.i("CrossCode", "[Steam] profile: name=$finalName avatar=$finalHash")
        return Profile(finalName, finalHash)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // All Steam Cloud filenames for this app (to discover the exact save path).
    fun listFiles(): List<String> =
        cloud.getAppFileListChange(APP_ID).get().files.map { it.filename }

    // Cloud modification time of a file (epoch millis), or null if it isn't in the cloud.
    fun cloudTimestampMillis(filename: String): Long? {
        val list = cloud.getAppFileListChange(APP_ID).get()
        val file = list.files.firstOrNull { it.filename == filename } ?: return null
        return file.timestamp.time
    }

    // Download a cloud file to dest. Returns true on success.
    fun pull(filename: String, dest: File): Boolean {
        val info = cloud.clientFileDownload(APP_ID, filename).get()
        if (info.urlHost.isNullOrEmpty()) {
            Log.e("CrossCode", "[Steam] no download URL for $filename")
            return false
        }

        val scheme = if (info.useHttps) "https" else "http"
        val conn = URL("$scheme://${info.urlHost}${info.urlPath}").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        info.requestHeaders.forEach { conn.setRequestProperty(it.name, it.value) }

        var bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()

        // Steam zips the payload when the raw (uncompressed) size differs from the served size.
        if (info.rawFileSize > 0 && info.rawFileSize != info.fileSize && isZip(bytes)) {
            bytes = unzipSingleEntry(bytes)
        }

        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        Log.i("CrossCode", "[Steam] pulled $filename (${bytes.size} bytes) -> ${dest.absolutePath}")
        return true
    }

    // Upload a local file to Steam Cloud under `filename`. Returns true on success.
    // If the cloud already holds byte-identical content, this is a no-op success: Steam rejects a
    // commit when nothing changed (commitFileUpload returns false), so we detect that up front via
    // the cloud file's SHA-1 and report "already up to date" instead of a spurious failure.
    fun push(filename: String, src: java.io.File): Boolean {
        val bytes = src.readBytes()
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes)
        val size = bytes.size

        val cloudSha = runCatching {
            cloud.getAppFileListChange(APP_ID).get().files.firstOrNull { it.filename == filename }?.shaFile
        }.getOrNull()
        if (cloudSha != null && cloudSha.contentEquals(sha)) {
            Log.i("CrossCode", "[Steam] push $filename: already up to date ($size bytes, sha match)")
            return true
        }

        val info = cloud.beginFileUpload(
            APP_ID, size, size, sha, Date(), filename,
            /* platformsToSync */ UInt.MAX_VALUE.toInt(),
            /* cellId */ 0,
            /* canEncrypt */ false,
            /* isSharedFile */ false,
            /* deprecatedRealm */ null,
            /* uploadBatchId */ 0L
        ).get()

        var uploadedOk = true
        try {
            for (block in info.blockRequests) {
                val scheme = if (block.useHttps) "https" else "http"
                val conn = URL("$scheme://${block.urlHost}${block.urlPath}").openConnection() as HttpURLConnection
                conn.requestMethod = if (block.httpMethod == 2) "POST" else "PUT"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                block.requestHeaders.forEach { conn.setRequestProperty(it.name, it.value) }
                conn.setRequestProperty("Content-Type", "application/octet-stream")

                val body = block.explicitBodyData?.takeIf { it.isNotEmpty() }
                    ?: bytes.copyOfRange(block.blockOffset.toInt(), (block.blockOffset + block.blockLength).toInt())
                conn.outputStream.use { it.write(body) }

                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) {
                    uploadedOk = false
                    Log.e("CrossCode", "[Steam] block upload HTTP $code for $filename")
                    break
                }
            }
        } catch (t: Throwable) {
            uploadedOk = false
            Log.e("CrossCode", "[Steam] block upload failed for $filename", t)
        }

        // Commit even on failure (transferSucceeded=false) so Steam releases the upload slot.
        val committed = cloud.commitFileUpload(uploadedOk, APP_ID, sha, filename).get()
        Log.i("CrossCode", "[Steam] push $filename: $size bytes, uploadedOk=$uploadedOk committed=$committed")
        return uploadedOk && committed
    }

    fun disconnect() {
        try { steamUser.logOff() } catch (_: Throwable) {}
        try { client.disconnect() } catch (_: Throwable) {}
        stopPump()
    }

    private fun isZip(b: ByteArray) =
        b.size >= 4 && b[0].toInt() == 0x50 && b[1].toInt() == 0x4B && b[2].toInt() == 0x03 && b[3].toInt() == 0x04

    private fun unzipSingleEntry(zip: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            zis.nextEntry ?: return zip
            return zis.readBytes()
        }
    }

    private fun startPump() {
        pumping = true
        pumpThread = Thread {
            while (pumping) manager.runWaitCallbacks(1000)
        }.apply { isDaemon = true; name = "SteamCloudCallbacks"; start() }
    }

    private fun stopPump() {
        pumping = false
        pumpThread?.join(2000)
        pumpThread = null
    }

    companion object {
        const val APP_ID = 368340  // CrossCode
    }
}
