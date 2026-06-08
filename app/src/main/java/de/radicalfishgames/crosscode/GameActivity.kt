package de.radicalfishgames.crosscode

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.radicalfishgames.crosscode.features.*
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import de.radicalfishgames.crosscode.steam.SteamCloudSync
import org.json.JSONTokener
import java.io.File


class GameActivity : AppCompatActivity() {

    val errorHandler = ErrorHandler(this)

    private lateinit var gameWrapper: GameWrapper

    // This SharedPreferences files is the one the preference activity automatically generates
    val preferences: SharedPreferences
         get() = getSharedPreferences("de.radicalfishgames.crosscode_preferences", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_game)

        startGame()
    }

    private fun startGame(){
        Thread.setDefaultUncaughtExceptionHandler(errorHandler)

        val nativeControllerActive = isNativeControllerActive()
        Log.d("CrossCode", "Native controller found: $nativeControllerActive")


        val dataFilesPath = this.getExternalFilesDir(null)
        if (dataFilesPath == null) {
            Toast.makeText(this@GameActivity, "Storage permission denied", Toast.LENGTH_LONG).show()
            return
        }
        val gameDir = "${dataFilesPath.absolutePath}/CrossCode"
        Log.d("CrossCode", "Game directory: $gameDir")

        var gameEntryPoint = "assets/node-webkit.html"
        val modLoaderEntryFile = (File("$gameDir/ccloader/index.html") or File("$gameDir/ccloader/main.html")) or File("$gameDir/ccloader3/main.html")
        var modLoaderPresent = false
        if(modLoaderEntryFile.exists()){
            Log.d("CrossCode", "Modloader found!")
            gameEntryPoint = modLoaderEntryFile.canonicalPath.replace(Regex("(.*:\\d+/)"), "")
            modLoaderPresent = true
        }

        Log.d("CrossCode", "Game entry point: $gameEntryPoint Attempting to load.")


        gameWrapper = GameWrapper(findViewById<android.webkit.WebView>(R.id.game_view), modLoaderPresent, gameDir)
        gameWrapper.initWebView()

        // Order is important, since some of these depend on one another and this is also the initialization order
        gameWrapper.features.addAll(listOf(
            ModListProvider(gameWrapper, this),
            ExtensionLoadingFix(gameWrapper, this),
            GameEventManager(gameWrapper, this),
            VirtualControllerFeature(gameWrapper, this),
            AutoLayoutSwitchFeature(gameWrapper, this),
            CutsceneLayoutDisableFeature(gameWrapper, this),
            OverlayTransparencyFeature(gameWrapper, this),
            CloudSaveFeature(gameWrapper, this),
            QuitMenuFeature(gameWrapper, this),
            ImportExportFeature(gameWrapper, this),
            HapticFeedbackFeature(gameWrapper, this),
            OverlayScaleFeature(gameWrapper, this)
        ))

        // Bridge the in-game "Exit" button (added by QuitMenuFeature) to the cloud-save quit flow.
        gameWrapper.exposeJSInterface(QuitBridge(), "CCAndroid")

        gameWrapper.loadGame(gameEntryPoint)

    }

    override fun onBackPressed() {
        // The system back gesture/button always offers a way out (essential on handhelds like the
        // AYN Thor, where a native controller is always present so the old code blocked back entirely).
        // A controller's B button is filtered out in onKeyDown below so it doesn't trigger this.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quit CrossCode?")
            .setMessage("Your progress will be saved to Steam Cloud.")
            .setPositiveButton("Quit") { _, _ -> quitWithCloudSync() }
            .setNegativeButton("Keep playing", null)
            .setOnDismissListener { goIntoFullScreen() }
            .show()
    }

    // On quit: pull the current save out of the WebView, upload it to Steam Cloud, then return to
    // the launcher. (Last-write-wins for now; timestamp-based conflict resolution is a follow-up.)
    private fun quitWithCloudSync() {
        val prefs = getSharedPreferences("steam", Context.MODE_PRIVATE)
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        val saveFile = File(getExternalFilesDir(null), "cloudsave/cc.save")

        gameWrapper.runJS("localStorage.getItem('cc.save')", ValueCallback { result ->
            try {
                if (result != null && result != "null") {
                    (JSONTokener(result).nextValue() as? String)?.let {
                        saveFile.parentFile?.mkdirs()
                        saveFile.writeText(it)
                    }
                }
            } catch (e: Exception) {
                Log.e("CrossCode", "[Steam] quit: extracting save failed", e)
            }

            val offlineSession = prefs.getBoolean("sessionOffline", false)
            if (offlineSession || account == null || token == null || !saveFile.exists() || saveFile.length() == 0L) {
                // Offline this session (manual toggle, or couldn't reach Steam at launch), not signed
                // in, or nothing to push: keep the save local and DON'T attempt the cloud. The next
                // online PLAY reconciles it via lastSyncedSha (local-changed -> pushed up).
                if (offlineSession && saveFile.exists() && saveFile.length() > 0L) {
                    Toast.makeText(this, "Saved locally — will sync to Steam Cloud next time you're online.", Toast.LENGTH_SHORT).show()
                }
                returnToLauncher()
                return@ValueCallback
            }

            val progress = androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Saving your progress to Steam Cloud…")
                .setCancelable(false)
                .show()

            Thread {
                var msg = "Progress saved to Steam Cloud."
                try {
                    val sync = SteamCloudSync(account, token)
                    try {
                        if (sync.connect() == SteamCloudSync.ConnectResult.OK) {
                            val name = sync.findSave()?.name ?: "%WinAppDataLocal%cc.save"
                            msg = when (sync.push(name, saveFile)) {
                                SteamCloudSync.PushResult.UPLOADED, SteamCloudSync.PushResult.UNCHANGED -> {
                                    prefs.edit().putString("lastSyncedSha", SteamCloudSync.sha1Hex(saveFile)).apply()
                                    "Progress saved to Steam Cloud."
                                }
                                SteamCloudSync.PushResult.REFUSED_SMALLER ->
                                    "Your cloud save looks more complete — saved this run locally and left the cloud untouched."
                                SteamCloudSync.PushResult.FAILED ->
                                    "Couldn't upload to Steam Cloud — saved locally, will retry next time."
                            }
                        } else {
                            msg = "Couldn't reach Steam Cloud — saved locally, will retry next time."
                        }
                    } finally {
                        sync.disconnect()
                    }
                } catch (t: Throwable) {
                    Log.e("CrossCode", "[Steam] quit-push failed", t)
                    msg = "Cloud save failed — your progress is saved locally."
                }
                runOnUiThread {
                    try { progress.dismiss() } catch (_: Throwable) {}
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    returnToLauncher()
                }
            }.apply { isDaemon = true; name = "QuitPush"; start() }
        })
    }

    private fun returnToLauncher() {
        startActivity(Intent(this, SteamLoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // Exposed to the game's JS as window.CCAndroid; called by the title-menu "Exit" button.
    inner class QuitBridge {
        @JavascriptInterface
        fun quit() {
            runOnUiThread { quitWithCloudSync() }
        }
    }

    // A controller's B button is sometimes delivered as KEYCODE_BACK. Consume those (the game reads
    // B via the gamepad API) so only the actual system back gesture/button reaches onBackPressed.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event != null &&
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun isNativeControllerActive(): Boolean {
        Log.d("CrossCode", "Searching for native controller...")

        InputDevice.getDeviceIds().forEach { deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@forEach

            if(isController(device)){
                Log.d("CrossCode", "Found device with gamepad and joystick support: ${device.name}")
                Log.v("CrossCode", device.toString())
                return true
            }
        }

        return false
    }

    private fun isController(device: InputDevice): Boolean {
        // SOURCE_DPAD is apparently not how a gamepad is labelled, despite having a DPad available
        val hasControllerSources = device.supportsSource(InputDevice.SOURCE_GAMEPAD) && device.supportsSource(InputDevice.SOURCE_JOYSTICK)

        if(hasControllerSources && device.name.equals("uinput-fpc")) {
            Log.w("CrossCode", "Ignoring input device uinput-fpc as it's known to be a fingerprint scanner disguising as a controller: https://github.com/libgdx/gdx-controllers/issues/9")
            return false
        }

        return hasControllerSources
    }

    override fun onPause() {
        super.onPause()

        Log.d("CrossCode", "Pausing game.")

        gameWrapper.onPause()
    }

    override fun onResume() {
        super.onResume()

        Log.d("CrossCode", "Resuming game.")

        gameWrapper.onResume()

        goIntoFullScreen()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        gameWrapper.getFeature(ImportExportFeature::class)
            .onActivityResult(requestCode, resultCode, data)

        super.onActivityResult(requestCode, resultCode, data)
    }

    fun goIntoFullScreen(){
        runOnUiThread {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }
}
