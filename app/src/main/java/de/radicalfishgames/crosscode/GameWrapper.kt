package de.radicalfishgames.crosscode

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import de.radicalfishgames.crosscode.features.Feature
import java.util.*
import kotlin.reflect.KClass


class GameWrapper(private val webView: WebView, val modLoaderPresent: Boolean, val gameDir: String) {

    val features = LinkedList<Feature>()

    var blockWebViewClicks = true

    private val handler = Handler()

    private var calledPostGameLoad = false

    private val webViewClient = GameWebViewClient(this)

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun initWebView() {

        Log.d("CrossCode, ", "Initializing WebView")

        // Enable Chrome DevTools (chrome://inspect) for this WebView so we can debug
        // the game's JS console, inspect localStorage, and drive cloud-save sync.
        WebView.setWebContentsDebuggingEnabled(true)

        webView.setInitialScale(0)
        webView.isVerticalScrollBarEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Prevents screen turning bright from the focus effect when using a controller
            // on some devices.
            webView.defaultFocusHighlightEnabled = false
        }

        webView.settings.apply {

            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = webViewClient

        // CCLoader3 serves mods via a service worker. Service-worker requests bypass the
        // WebViewClient above, so route them through the same file server, or registration
        // fails and the game never boots. (ServiceWorkerController is API 24+.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return webViewClient.serve(request)
                }
            })
        }

        // Disable clicking the game, usually while custom controls are active
        webView.setOnTouchListener { view: View, motionEvent: MotionEvent ->
            // Allow ACTION_UP to prevent accidentally locking into aiming when switching this boolean
            blockWebViewClicks && motionEvent.action != MotionEvent.ACTION_UP
        }
    }

    fun loadGame(gameEntryPoint: String) {
        exposeJSInterface(this, "CrossAndroid")

        for(feature in features){
            feature.onPreGamePageLoad()
        }

        // Load the game files, and also set a flag for the modloader to recognize that
        // it's being executed in CrossAndroid
        webView.loadUrl(webViewClient.buildVirtualUrl()
            .path(gameEntryPoint)
            .encodedQuery("crossandroid=true")
            .build()
            .toString())
    }


    // Fired when the main document starts loading, before CCLoader/the engine run. Lets features
    // seed state (e.g. the save) into the page before the engine reads it on a cold first boot.
    fun onPageStarted(){
        for(feature in features){
            feature.onGamePageStarted()
        }
    }

    fun onPageLoaded(){

        Log.d("CrossCode", "Game page loaded.")

        if(!modLoaderPresent){
            Log.d("CrossCode", "Starting game manually.")

            startCrossCodeManually()

            executePostGameLoad()

        }else{
            Log.d("CrossCode", "Modloader is present, it will start the game for us.")

        }
    }

    @JavascriptInterface
    fun executePostGameLoad(){
        if(calledPostGameLoad){
            return
        }
        calledPostGameLoad = true

        handler.post {
            for(feature in features){
                feature.onPostGamePageLoad()
            }
        }
    }

    fun <T : Feature> getFeature(ofType: KClass<T>): T {
        return features.first { ofType.isInstance(it) } as T
    }

    @SuppressLint("JavascriptInterface")
    fun exposeJSInterface(obj: Any, name:String) = webView.addJavascriptInterface(obj, name)

    private fun startCrossCodeManually() = runJS("doStartCrossCodePlz()")

    fun onResume() = runJS("window.dispatchEvent(new FocusEvent(\"focus\"));")
    fun onPause() {
        for (feature in features) feature.onPause()
        runJS("window.dispatchEvent(new FocusEvent(\"blur\"));")
    }

    fun runJS(js: String, callback: ValueCallback<String>? = null){
        webView.evaluateJavascript(js, callback)
    }
}