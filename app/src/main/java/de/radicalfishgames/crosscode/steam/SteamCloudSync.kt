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

    // OK = logged on. AUTH_FAILED = Steam answered but rejected the token (re-login needed).
    // NETWORK_FAILED = couldn't reach Steam at all (offline). Callers MUST treat NETWORK_FAILED as
    // "play the local save, keep the saved login" — NOT as an expired login (that would wipe the
    // token and refuse to launch when you're simply offline).
    enum class ConnectResult { OK, AUTH_FAILED, NETWORK_FAILED }

    fun connect(timeoutSec: Long = 20): ConnectResult {
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
        try {
            client.connect()
        } catch (t: Throwable) {
            Log.e("CrossCode", "[Steam] connect() threw (offline?)", t)
            return ConnectResult.NETWORK_FAILED
        }
        if (!loggedOn.await(timeoutSec, TimeUnit.SECONDS)) {
            Log.e("CrossCode", "[Steam] couldn't reach Steam (offline?)")
            return ConnectResult.NETWORK_FAILED
        }
        Log.i("CrossCode", "[Steam] logon result: $result")
        return if (result == EResult.OK) ConnectResult.OK else ConnectResult.AUTH_FAILED
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

    data class CloudFile(val name: String, val shaHex: String, val size: Int, val timestampMillis: Long)

    // Locate the cloud save (canonical cc.save; skip Steam's escaped %...PERCENT...% variants) and
    // return its identity (SHA-1 + size + mtime), or null if there's no cloud save yet.
    fun findSave(): CloudFile? {
        val files = cloud.getAppFileListChange(APP_ID).get().files
        val f = files.firstOrNull { it.filename == "cc.save" }
            ?: files.firstOrNull { it.filename.endsWith("cc.save") && !it.filename.contains("PERCENT") }
            ?: return null
        val shaHex = f.shaFile?.joinToString("") { "%02x".format(it) } ?: ""
        return CloudFile(f.filename, shaHex, f.rawFileSize, f.timestamp.time)
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
        backupIfPresent(dest)          // keep the prior local save as .bak before overwriting
        writeAtomically(dest, bytes)
        Log.i("CrossCode", "[Steam] pulled $filename (${bytes.size} bytes) -> ${dest.absolutePath}")
        return true
    }

    // Upload a local file to Steam Cloud. Returns:
    //   UNCHANGED       - cloud already byte-identical (Steam rejects a no-op commit), so skip.
    //   REFUSED_SMALLER - the cloud holds a substantial save and this one is < half its size:
    //                     almost certainly a fresh/partial save written because the cloud slots
    //                     never loaded. We refuse rather than clobber a good save; caller surfaces it.
    //   UPLOADED        - uploaded and committed.
    //   FAILED          - empty source, or an upload/commit error.
    enum class PushResult { UPLOADED, UNCHANGED, REFUSED_SMALLER, FAILED }

    fun push(filename: String, src: java.io.File): PushResult {
        val bytes = src.readBytes()
        if (bytes.isEmpty()) {
            Log.w("CrossCode", "[Steam] refusing to push an empty $filename")
            return PushResult.FAILED
        }
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes)
        val size = bytes.size

        val cloudFile = runCatching {
            cloud.getAppFileListChange(APP_ID).get().files.firstOrNull { it.filename == filename }
        }.getOrNull()
        if (cloudFile != null) {
            val cloudSha = cloudFile.shaFile
            if (cloudSha != null && cloudSha.contentEquals(sha)) {
                Log.i("CrossCode", "[Steam] push $filename: already up to date ($size bytes, sha match)")
                return PushResult.UNCHANGED
            }
            val cloudSize = cloudFile.rawFileSize
            if (cloudSize >= 4096 && size < cloudSize / 2) {
                Log.w("CrossCode", "[Steam] refusing to overwrite $cloudSize-byte cloud save with $size-byte save")
                return PushResult.REFUSED_SMALLER
            }
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
        return if (uploadedOk && committed) PushResult.UPLOADED else PushResult.FAILED
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

    // Keep the current file as <name>.bak before it gets overwritten — insurance against any
    // sync mistake; the previous save is always one copy away.
    private fun backupIfPresent(f: File) {
        if (f.exists() && f.length() > 0) runCatching {
            f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true)
        }
    }

    // Write via a temp file + rename so a crash mid-write can never leave a half-written save.
    private fun writeAtomically(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
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

        // SHA-1 (hex) of a file's bytes, or null if it's missing/empty. Lets the launcher tell
        // whether the local save matches the cloud and/or the last-synced baseline.
        fun sha1Hex(f: File): String? =
            if (f.exists() && f.length() > 0)
                MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            else null
    }
}
