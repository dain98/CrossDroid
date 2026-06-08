package de.radicalfishgames.crosscode

import android.net.Uri
import android.util.Log
import android.webkit.*
import java.io.*
import java.util.*

class GameWebViewClient(private val gameWrapper: GameWrapper) : WebViewClient() {

    private val gameDir = File(gameWrapper.gameDir).canonicalFile

    override fun onPageFinished(view: WebView?, url: String?) {
        gameWrapper.onPageLoaded()
    }

    // A request interceptor is used for two purposes. Firstly, ES6 imports refuse to work if the imported
    // file is not returned with the appropriate MIME type (such as text/javascript), and files returned
    // from file:// don't get content types at all, so we perform our own MIME type detection. Secondly, the
    // game is "served" from a virtual domain with HTTPS, so the browser thinks that this is a real website
    // and not some random page loaded from file://, and thus allows using APIs such as fetch() and enables
    // the regular same-origin policy, while in reality no HTTP server is running and the requests to that
    // domain are handled here by us. Additionally, the HTTPS scheme is used to enable installation of
    // service workers. This technique (and where I found the domain name reserved for this purpose) is
    // described in more detail here:
    // https://developer.android.com/guide/webapps/load-local-content
    // https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        return serve(request)
    }

    // Shared file-serving logic, used by BOTH the WebViewClient (normal page requests) and the
    // ServiceWorkerClient (the service worker script + the fetches it makes). Service-worker
    // requests are NOT routed through WebViewClient.shouldInterceptRequest, so without also
    // wiring this into a ServiceWorkerClient, CCLoader3's service worker fails to register
    // ("An unknown error occurred when fetching the script") and the game black-screens.
    fun serve(request: WebResourceRequest?): WebResourceResponse? {
        if(request == null){
            return null
        }

        val url = request.url
        val urlPath = url.path
        if(!(url.scheme == VIRTUAL_ORIGIN_SCHEME && url.authority == VIRTUAL_ORIGIN_DOMAIN && urlPath != null)){
            return null
        }

        val requestedFile = try {
            when {
                // Game assets requested explicitly under /assets (kept from upstream).
                urlPath.startsWith("/assets") -> File(gameDir, urlPath).canonicalFile
                // CCLoader's own files arrive as the full on-device path, which exists as-is.
                File(urlPath).exists() -> File(urlPath).canonicalFile
                // CCLoader3 ANDROID mode sets gameAssetsDir="/", so the game/CCLoader request
                // assets as root paths (/data/..., /media/..., /page/..., /mods/...). Serve those
                // from <gameDir>/assets. Without this they 404 and the game never starts.
                else -> File(File(gameDir, "assets"), urlPath.trimStart('/')).canonicalFile
            }
        } catch (e: IOException) {
            Log.w("CrossCode", "Failed to resolve path ${urlPath}!", e)
            return WebResourceResponse(null, null, null)
        }

        if(!requestedFile.canRead()){
            Log.w("CrossCode", "Path $urlPath not found!")
            return WebResourceResponse(null, null, null)
        }

        if(requestedFile.isDirectory){
            // Return an empty successful response for directories, CCLoader depends on that for
            // checking whether they exist.
            return WebResourceResponse(null, null, ByteArrayInputStream(byteArrayOf()))
        }

        val stream = FileInputStream(requestedFile)

        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(requestedFile.extension)
            ?: FallbackMimeTypeMap.getMimeTypeFromExtension(requestedFile.extension)

        return WebResourceResponse(mimeType, null, stream)
    }

    fun buildVirtualUrl(): Uri.Builder = Uri.Builder().scheme(VIRTUAL_ORIGIN_SCHEME).authority(VIRTUAL_ORIGIN_DOMAIN).appendQueryParameter("crossandroid", "true")

    companion object {
        const val VIRTUAL_ORIGIN_SCHEME = "https"
        const val VIRTUAL_ORIGIN_DOMAIN = "appassets.androidplatform.net"
    }
}


object FallbackMimeTypeMap {

    private val extensionsToMimeTypesMap = mutableMapOf<String, String>()

    init {
        // https://github.com/nginx/nginx/blob/release-1.22.0/conf/mime.types
        // https://pagure.io/mailcap/blob/4a12cc7caeb4a74626e8e6aedf38e7223b28e982/f/mime.types
        // https://android.googlesource.com/platform/libcore/+/refs/tags/android-9.0.0_r58/luni/src/main/java/libcore/net/MimeUtils.java
        // https://github.com/nwjs/chromium.src/blob/f65a261f2999a769ac2d718630a8fd5f4b5bb9ac/net/base/mime_util.cc#L146-L230

        // Web stuff
        add("text/html", "html", "htm")
        add("text/css", "css")
        add("text/javascript", "js", "mjs")
        add("application/json", "json", "map")
        add("text/xml", "xml", "xslt", "xsl", "xsd")
        add("application/xhtml+xml", "xhtml", "xht")
        add("application/wasm", "wasm")
        add("font/woff", "woff")
        add("font/woff2", "woff2")
        add("font/ttf", "ttf")
        add("font/otf", "otf")
        add("text/plain", "txt", "text")
        add("text/markdown", "md")

        // Video
        add("video/webm", "webm") // Must come before audio/webm
        add("video/ogg", "ogv")
        add("video/mp4", "mp4")

        // Audio
        add("audio/ogg", "ogg", "oga", "opus")
        add("audio/webm", "webm")
        add("audio/mpeg", "mp3")
        add("audio/flac", "flac")
        add("audio/wav", "wav")

        // Images
        add("image/png", "png")
        add("image/jpeg", "jpeg", "jpg")
        add("image/svg+xml", "svg", "svgz")
        add("image/webp", "webp")
        add("image/gif", "gif")
        add("image/bmp", "bmp")
        add("image/vnd.microsoft.icon", "ico")

        // Archives and compression algorithms
        add("application/zip", "zip")
        add("application/gzip", "gz", "tgz")
        add("application/x-tar", "tar")

        // CrossCode-specific
        add("application/zip", "ccmod")
    }

    fun add(type: String, vararg exts: String) {
        for(ext in exts){
            if(ext !in extensionsToMimeTypesMap){
                extensionsToMimeTypesMap[ext] = type
            }
        }
    }

    fun getMimeTypeFromExtension(extension: String): String? {
        return if(extension.isNotEmpty()) extensionsToMimeTypesMap[extension.lowercase(Locale.ROOT)] else null
    }
}