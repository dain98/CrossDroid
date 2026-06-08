package de.radicalfishgames.crosscode.steam

import android.util.Log
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
import com.squareup.zstd.okio.zstdDecompress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.buffer
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// Downloads CrossCode's game files from the Steam CDN (the same flow as the StS2 launcher's
// DepotDownloader.cs, ported to JavaSteam). Selective: only the web game (assets/ + package.json),
// not CrossCode's Windows binaries — which is exactly what the WebView runtime needs. Each file is
// verified against the manifest SHA-1 and written via a temp file, so interrupted downloads resume.
class SteamDepotDownloader(private val accountName: String, private val refreshToken: String) {

    private val client = SteamClient()
    private val manager = CallbackManager(client)
    private val steamUser: SteamUser = client.getHandler(SteamUser::class.java)!!
    private val steamApps: SteamApps = client.getHandler(SteamApps::class.java)!!
    private val steamContent: SteamContent = client.getHandler(SteamContent::class.java)!!
    private val cdn = Client(client)
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var pumping = false
    private var pumpThread: Thread? = null
    private val serverCursor = AtomicInteger(0)

    data class Progress(
        val totalBytes: Long,
        val downloadedBytes: Long,
        val totalFiles: Int,
        val completedFiles: Int,
        val currentFile: String
    ) {
        val percent: Int get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }

    fun connect(timeoutSec: Long = 30): Boolean {
        val loggedOn = CountDownLatch(1)
        var result: EResult? = null
        manager.subscribe(ConnectedCallback::class.java) {
            steamUser.logOn(LogOnDetails().apply {
                username = accountName
                accessToken = refreshToken
                shouldRememberPassword = true
            })
        }
        manager.subscribe(LoggedOnCallback::class.java) { cb -> result = cb.result; loggedOn.countDown() }
        startPump()
        client.connect()
        if (!loggedOn.await(timeoutSec, TimeUnit.SECONDS)) {
            Log.e(TAG, "[Depot] logon timed out"); return false
        }
        Log.i(TAG, "[Depot] logon result: $result")
        return result == EResult.OK
    }

    // Download the CrossCode web game into destDir/CrossCode (so destDir/CrossCode/assets exists).
    // Returns true on success. `onProgress`/`log` surface to the UI.
    fun download(crossCodeDir: File, onProgress: (Progress) -> Unit, log: (String) -> Unit): Boolean {
        crossCodeDir.mkdirs()

        log("Fetching app info…")
        val accessToken = steamApps.picsGetAccessTokens(APP_ID).runBlock().appTokens[APP_ID] ?: 0L
        val productInfo = steamApps.picsGetProductInfo(PICSRequest(APP_ID, accessToken)).runBlock()
        val appInfo = productInfo.results
            .filterIsInstance<PICSProductInfoCallback>()
            .firstNotNullOfOrNull { it.apps[APP_ID] }
            ?: run { log("Couldn't read app info from Steam."); return false }

        val depots = parseDepots(appInfo.keyValues.get("depots"), log)
        if (depots.isEmpty()) { log("No downloadable depots found."); return false }

        log("Locating CDN servers…")
        val servers = pickServers()
        if (servers.isEmpty()) { log("No CDN servers available."); return false }
        log("Using ${servers.size} CDN servers")

        // Resolve each depot's key + manifest, then collect just the web-game files.
        data class DepotCtx(val depotId: Int, val key: ByteArray, val manifest: DepotManifest)
        val ctxs = ArrayList<DepotCtx>()
        for ((depotId, manifestId) in depots) {
            val keyCb = steamApps.getDepotDecryptionKey(depotId, APP_ID).runBlock()
            if (keyCb.result != EResult.OK) { log("No depot key for $depotId (${keyCb.result}) — skipping"); continue }
            val reqCode = runBlocking { steamContent.getManifestRequestCode(depotId, APP_ID, manifestId, BRANCH, parentScope = scope).await() }.toLong()
            if (reqCode == 0L) { log("No manifest code for depot $depotId (do you own CrossCode?)"); continue }
            val manifest = downloadManifest(depotId, manifestId, reqCode, servers, keyCb.depotKey, log)
            if (manifest == null) { log("Failed to download manifest for depot $depotId"); continue }
            ctxs.add(DepotCtx(depotId, keyCb.depotKey, manifest))
        }

        val targets = ctxs.flatMap { ctx ->
            ctx.manifest.files
                .filter { !it.flags.contains(EDepotFileFlag.Directory) && isWanted(normalize(it.fileName)) }
                .map { ctx to it }
        }
        if (targets.isEmpty()) { log("No game files matched in the depot manifests."); return false }

        val totalBytes = targets.sumOf { it.second.totalSize }
        val downloadedBytes = AtomicLong(0)
        val completedFiles = AtomicInteger(0)
        log("Downloading ${targets.size} files (${formatSize(totalBytes)})…")
        onProgress(Progress(totalBytes, 0, targets.size, 0, ""))

        // Many small asset files — download in parallel (latency-bound). Transient chunk failures
        // (~1 in 1500) are retried in additional passes over just the failed files.
        var remaining: List<Pair<DepotCtx, FileData>> = targets
        var pass = 0
        while (remaining.isNotEmpty() && pass < FILE_PASSES) {
            pass++
            val stillFailed = java.util.concurrent.CopyOnWriteArrayList<Pair<DepotCtx, FileData>>()
            val pool = Executors.newFixedThreadPool(CONCURRENCY)
            try {
                pool.invokeAll(remaining.map { (ctx, file) ->
                    Callable {
                        val rel = normalize(file.fileName)
                        val out = File(crossCodeDir, rel)
                        out.parentFile?.mkdirs()
                        try {
                            // Resume skip: size match (chunks + file were SHA-verified when first written,
                            // so re-hashing 1.5 GB on every resume is unnecessary).
                            if (out.exists() && out.length() == file.totalSize) {
                                downloadedBytes.addAndGet(file.totalSize)
                            } else {
                                downloadFile(ctx.depotId, ctx.key, file, out, servers, log) { delta ->
                                    downloadedBytes.addAndGet(delta)
                                }
                            }
                            val done = completedFiles.incrementAndGet()
                            onProgress(Progress(totalBytes, downloadedBytes.get(), targets.size, done, rel))
                        } catch (t: Throwable) {
                            stillFailed.add(ctx to file)
                        }
                    }
                })
            } finally {
                pool.shutdown()
            }
            remaining = stillFailed
            if (remaining.isNotEmpty()) log("Retrying ${remaining.size} file(s) (pass ${pass + 1} of $FILE_PASSES)…")
        }

        if (remaining.isNotEmpty()) {
            log("${remaining.size} file(s) failed after $FILE_PASSES passes: " +
                remaining.joinToString(", ") { "d${it.first.depotId}/${normalize(it.second.fileName)}" })
            return false
        }
        log("CrossCode downloaded.")
        return true
    }

    // Keep only the web game CrossCode actually runs from.
    private fun isWanted(path: String) = path == "package.json" || path.startsWith("assets/")

    private fun normalize(name: String) = name.replace('\\', '/')

    private fun parseDepots(depotsKv: `in`.dragonbra.javasteam.types.KeyValue, log: (String) -> Unit): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        for (depot in depotsKv.children) {
            val depotId = depot.name?.toIntOrNull() ?: continue
            // Skip non-Windows depots (we want the desktop NW.js build's assets).
            val oslist = depot.get("config").get("oslist").value
            if (!oslist.isNullOrEmpty() && !oslist.contains("windows")) { log("Skipping depot $depotId (os=$oslist)"); continue }
            val gid = depot.get("manifests").get(BRANCH).get("gid").value?.toLongOrNull() ?: continue
            log("Found depot $depotId manifest $gid")
            out.add(depotId to gid)
        }
        return out
    }

    private fun pickServers(): List<Server> {
        val all = runBlocking { steamContent.getServersForSteamPipe(parentScope = scope).await() }
        val cdn = all.filter { it.type == "SteamCache" || it.type == "CDN" }
        return if (cdn.isNotEmpty()) cdn else all
    }

    private fun nextServer(servers: List<Server>): Server =
        servers[(serverCursor.getAndIncrement() % servers.size + servers.size) % servers.size]

    // CDN auth tokens are per (depot, server host). Using one host's token on another host makes
    // some servers return an error body (HTTP 200) that decrypts to garbage → decompress fails.
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun tokenFor(depotId: Int, host: String): String? {
        val v = tokenCache.computeIfAbsent("$depotId|$host") {
            runCatching {
                runBlocking { steamContent.getCDNAuthToken(APP_ID, depotId, host, parentScope = scope).await() }
            }.getOrNull()?.takeIf { it.result == EResult.OK }?.token ?: ""
        }
        return v.ifEmpty { null }
    }

    private fun downloadManifest(
        depotId: Int, manifestId: Long, reqCode: Long, servers: List<Server>, key: ByteArray,
        log: (String) -> Unit
    ): DepotManifest? {
        for (attempt in 0 until MAX_RETRIES) {
            val server = nextServer(servers)
            try {
                return cdn.downloadManifestFuture(depotId, manifestId, reqCode, server, key, null, tokenFor(depotId, server.host)).get()
            } catch (t: Throwable) {
                log("Manifest attempt ${attempt + 1} failed: ${t.message}")
            }
        }
        return null
    }

    private fun downloadFile(
        depotId: Int, key: ByteArray, file: FileData, out: File,
        servers: List<Server>, log: (String) -> Unit, onDelta: (Long) -> Unit
    ) {
        val tmp = File(out.parentFile, out.name + ".downloading")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(file.totalSize)
            for (chunk in file.chunks.sortedBy { it.offset }) {
                val buffer = ByteArray(chunk.uncompressedLength)
                var written = 0
                for (attempt in 0 until MAX_RETRIES) {
                    val server = nextServer(servers)
                    try {
                        // Suspend variant under our own runBlocking, NOT downloadDepotChunkFuture: the
                        // Future variant launches on the shared client scope, where one failure cancels
                        // every concurrent download ("Parent job is Cancelled"). This keeps failures local.
                        written = runBlocking { cdn.downloadDepotChunk(depotId, chunk, server, buffer, key, null, tokenFor(depotId, server.host)) }
                        if (verifyChunk(buffer, written, chunk.chunkID)) break else written = 0
                    } catch (t: Throwable) {
                        // JavaSteam only handles VZip/Zip chunks; Steam also stores some chunks raw
                        // (uncompressed), which it mis-routes and fails. Fetch + decrypt those ourselves;
                        // the SHA-1 check below makes this safe (only accepted if it's genuinely the data).
                        val raw = runCatching {
                            fetchDecryptRaw(depotId, chunk, server, key, tokenFor(depotId, server.host), buffer)
                        }.onFailure { log("fdr threw: ${it.message}") }.getOrDefault(-1)
                        if (raw > 0) {
                            if (verifyChunk(buffer, raw, chunk.chunkID)) { written = raw; break }
                            else log("fdr sha-mismatch raw=$raw")
                        }
                        log("Chunk attempt ${attempt + 1} failed: ${t.message}")
                    }
                }
                if (written == 0 && chunk.uncompressedLength > 0)
                    throw RuntimeException("Failed chunk for ${file.fileName} after $MAX_RETRIES tries")
                raf.seek(chunk.offset)
                raf.write(buffer, 0, written)
                onDelta(written.toLong())
            }
        }
        if (!verifyFile(tmp, file)) { tmp.delete(); throw RuntimeException("SHA-1 mismatch for ${file.fileName}") }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    // Manually fetch + decrypt a chunk for the case JavaSteam can't decompress: Steam stores some
    // chunks raw (uncompressed), which are neither "VZ" nor a PKZip, so its processor mis-routes and
    // fails. Returns bytes written to dest, or -1. The caller SHA-1-verifies, so this is safe.
    private fun fetchDecryptRaw(depotId: Int, chunk: ChunkData, server: Server, key: ByteArray, token: String?, dest: ByteArray): Int {
        val hex = chunk.chunkID?.joinToString("") { "%02X".format(it) } ?: return -1
        val scheme = if (server.protocol == Server.ConnectionProtocol.HTTP) "http" else "https"
        val query = token?.let { "?$it" } ?: ""
        val urlStr = "$scheme://${server.vHost}:${server.port}/depot/$depotId/chunk/$hex$query"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000; conn.readTimeout = 60_000
        val enc = try {
            val code = conn.responseCode
            if (code !in 200..299) { Log.i(TAG, "[Depot] fdr http $code $urlStr"); return -1 }
            conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
        if (enc.size <= 16) return -1

        // Same AES scheme as JavaSteam's DepotChunk: ECB-decrypt the 16-byte IV, then CBC-decrypt the body.
        val keySpec = SecretKeySpec(key, "AES")
        val ecb = Cipher.getInstance("AES/ECB/NoPadding"); ecb.init(Cipher.DECRYPT_MODE, keySpec)
        val iv = ByteArray(16); ecb.doFinal(enc, 0, 16, iv)
        val cbc = Cipher.getInstance("AES/CBC/PKCS5Padding"); cbc.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        val plain = cbc.doFinal(enc, 16, enc.size - 16)

        // Steam now compresses some chunks with Zstandard ("VSZ" header + a zstd frame), which
        // JavaSteam 1.6.0 doesn't handle (it only knows VZip/Zip → mis-routes → fails). Decompress
        // the zstd frame ourselves, reading exactly the uncompressed length (so we stop before the footer).
        if (plain.size >= 8 && plain[0] == 0x56.toByte() && plain[1] == 0x53.toByte() && plain[2] == 0x5A.toByte()) {
            var off = 4
            while (off <= plain.size - 4 && !(plain[off] == 0x28.toByte() && plain[off + 1] == 0xB5.toByte() &&
                    plain[off + 2] == 0x2F.toByte() && plain[off + 3] == 0xFD.toByte())) off++
            if (off > plain.size - 4) return -1
            val out = Buffer().write(plain, off, plain.size - off).zstdDecompress().buffer()
                .use { it.readByteArray(chunk.uncompressedLength.toLong()) }
            if (out.size > dest.size) return -1
            System.arraycopy(out, 0, dest, 0, out.size)
            return out.size
        }
        // VZip/Zip are JavaSteam's job; otherwise treat as raw (uncompressed) — the SHA-1 check gates it.
        if (plain.size >= 2 && plain[0] == 'V'.code.toByte() && plain[1] == 'Z'.code.toByte()) return -1
        if (plain.size >= 4 && plain[0] == 'P'.code.toByte() && plain[1] == 'K'.code.toByte()) return -1
        if (plain.size > dest.size) return -1
        System.arraycopy(plain, 0, dest, 0, plain.size)
        return plain.size
    }

    private fun verifyChunk(buffer: ByteArray, length: Int, chunkId: ByteArray?): Boolean {
        if (chunkId == null || chunkId.isEmpty()) return true
        val sha = MessageDigest.getInstance("SHA-1").digest(buffer.copyOf(length))
        return sha.contentEquals(chunkId)
    }

    private fun verifyFile(file: File, data: FileData): Boolean {
        if (!file.exists() || file.length() != data.totalSize) return false
        val expected = data.fileHash ?: return true
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().contentEquals(expected)
        } catch (t: Throwable) { false }
    }

    fun disconnect() {
        try { steamUser.logOff() } catch (_: Throwable) {}
        try { client.disconnect() } catch (_: Throwable) {}
        try { cdn.close() } catch (_: Throwable) {}
        stopPump()
    }

    private fun startPump() {
        pumping = true
        pumpThread = Thread { while (pumping) manager.runWaitCallbacks(1000) }
            .apply { isDaemon = true; name = "SteamDepotCallbacks"; start() }
    }

    private fun stopPump() { pumping = false; pumpThread?.join(2000); pumpThread = null }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    companion object {
        private const val TAG = "CrossCode"
        const val APP_ID = 368340
        private const val BRANCH = "public"
        private const val MAX_RETRIES = 5
        private const val CONCURRENCY = 8
        private const val FILE_PASSES = 4
    }
}
