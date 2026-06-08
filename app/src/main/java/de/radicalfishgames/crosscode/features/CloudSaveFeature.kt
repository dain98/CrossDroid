package de.radicalfishgames.crosscode.features

import android.util.Log
import android.webkit.ValueCallback
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

// Syncs CrossCode's entire save (the localStorage["cc.save"] blob, which is byte-identical
// to the desktop cc.save that Steam Cloud syncs) with a file on disk:
//   - on launch (before the engine reads it): inject the file's contents into localStorage   [PULL]
//   - on pause/exit: read localStorage["cc.save"] back out and write it to the file           [PUSH]
//
// On its own this is a manual, file-based cloud-save workflow (drop your PC cc.save in, play,
// copy it back). It is also the exact integration point for the Steam layer: download the
// cloud save into this file before launch, upload it from this file on exit. CrossCode itself
// already persists saves to localStorage across sessions, so this only bridges to the file.
class CloudSaveFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    // App-scoped external dir: Android/data/de.radicalfishgames.crosscode/files/cloudsave/cc.save
    // (reachable via adb/MTP for manual transfer; no storage permission required to write).
    private val saveFile = File(hostActivity.getExternalFilesDir(null), "cloudsave/cc.save")

    // PULL: seed localStorage from the file before the game reads it. Cloud-wins-on-launch,
    // mirroring Steam Cloud's model; timestamp-aware conflict resolution lands with the Steam layer.
    //
    // Seed at page-START (before CCLoader/the engine boot) so the save is present the first time the
    // engine reads it. Seeding only at onPostGamePageLoad lands too late on a cold first launch
    // (empty localStorage) — the title screen reads "no saves" before the inject, and it only
    // appeared to work on later launches because localStorage persisted the late-injected value.
    // The post-load call is kept as a harmless backup (idempotent re-seed of the same content).
    override fun onGamePageStarted() = seedSave("page-start")

    override fun onPostGamePageLoad() = seedSave("post-load")

    private fun seedSave(phase: String) {
        try {
            if (!saveFile.exists() || saveFile.length() == 0L) {
                Log.i(TAG, "[CloudSave] ($phase) no save file at ${saveFile.absolutePath}, leaving localStorage as-is")
                return
            }
            val content = saveFile.readText()
            // JSONObject.quote -> a safe, fully-escaped JS string literal
            runJS("localStorage.setItem('$KEY', ${JSONObject.quote(content)}); void 0;")
            Log.i(TAG, "[CloudSave] ($phase) injected ${content.length} chars into localStorage['$KEY']")
        } catch (e: Exception) {
            Log.e(TAG, "[CloudSave] ($phase) inject failed", e)
        }
    }

    // PUSH: read the current save out of localStorage and persist it to the file.
    override fun onPause() {
        try {
            runJS("localStorage.getItem('$KEY')", ValueCallback { result ->
                // evaluateJavascript returns the value JSON-encoded, or the literal "null"
                if (result == null || result == "null") {
                    Log.i(TAG, "[CloudSave] localStorage['$KEY'] empty, nothing to write")
                    return@ValueCallback
                }
                try {
                    val content = JSONTokener(result).nextValue() as? String ?: return@ValueCallback
                    saveFile.parentFile?.mkdirs()
                    saveFile.writeText(content)
                    Log.i(TAG, "[CloudSave] wrote ${content.length} chars to ${saveFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "[CloudSave] write failed", e)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "[CloudSave] onPause failed", e)
        }
    }

    companion object {
        private const val TAG = "CrossCode"
        private const val KEY = "cc.save"
    }
}
