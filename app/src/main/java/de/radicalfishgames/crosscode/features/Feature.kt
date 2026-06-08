package de.radicalfishgames.crosscode.features

import android.webkit.ValueCallback
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper


abstract class Feature(protected val gameWrapper: GameWrapper, protected val hostActivity: GameActivity) {
    
    open fun onPreGamePageLoad() {}
    open fun onPostGamePageLoad() {}

    // Called when the activity is paused (also fires on exit, before destroy). Use for
    // flushing state out of the WebView (e.g. reading the current save).
    open fun onPause() {}

    internal fun runJS(js: String, callback: ValueCallback<String>? = null) = gameWrapper.runJS(js, callback)
}