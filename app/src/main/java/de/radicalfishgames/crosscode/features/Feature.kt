package de.radicalfishgames.crosscode.features

import android.webkit.ValueCallback
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper


abstract class Feature(protected val gameWrapper: GameWrapper, protected val hostActivity: GameActivity) {
    
    open fun onPreGamePageLoad() {}

    // Called when the game page STARTS loading, before CCLoader and the engine boot. Use to seed
    // state the engine reads early in boot (e.g. the save blob in localStorage) so it is already
    // present the first time the engine reads it — onPostGamePageLoad fires too late on a cold
    // first launch (empty localStorage), only "working" later because localStorage persists.
    open fun onGamePageStarted() {}

    open fun onPostGamePageLoad() {}

    // Called when the activity is paused (also fires on exit, before destroy). Use for
    // flushing state out of the WebView (e.g. reading the current save).
    open fun onPause() {}

    internal fun runJS(js: String, callback: ValueCallback<String>? = null) = gameWrapper.runJS(js, callback)
}