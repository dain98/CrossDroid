package de.radicalfishgames.crosscode

import android.Manifest
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewStub
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.radicalfishgames.crosscode.preferences.PreferencesActivity
import java.io.File

class LaunchActivity : AppCompatActivity() {

    private var userConfirmedCCLoader = false
    private var userConfirmedCopiedInst = false
    private var userConfirmedDownloadedTools = false
    private var userConfirmedPackagedGameFiles = false

    private val handler = Handler()

    // Replaces kotlin-android-extensions synthetics (removed in Kotlin 2). All usages here are
    // View-level (click/visibility/enabled), so a plain View lookup is enough.
    private fun v(id: Int): View = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_launch)

        v(R.id.launch_button).setOnClickListener { launchGameActivity() }
        v(R.id.preferences_button).setOnClickListener { launchPreferencesActivity() }
        // The 'about_button' / Creditz About screen was dropped in the AGP 8 / Kotlin 2 migration.
    }

    override fun onResume() {
        super.onResume()

        handler.post {
            tryToAccessInstance()
        }
    }

    private fun tryToAccessInstance() {
        Log.i("CrossCode", "Attempting to access the CrossCode instance...")

        val sdcardPath = this.getExternalFilesDir(null)
        if (sdcardPath == null) {
            Toast.makeText(this@LaunchActivity, "Storage permission denied", Toast.LENGTH_LONG).show()
            return
        }
        val instanceDir = "${sdcardPath.absolutePath}/CrossCode"

        if (!File("${instanceDir}/assets").exists()) {
            instanceNotFound()
            return
        }
        instanceFound("${instanceDir}/assets")
    }

    private fun instanceFound(path: String){
        val normalLaunchFile = File("$path/node-webkit.html")
        val modLoaderLaunchFile = (File("$path/ccloader/index.html") or File("$path/ccloader/main.html")) or File("$path/ccloader3/main.html")

        if(!(normalLaunchFile.exists() || modLoaderLaunchFile.exists())){
            Toast.makeText(this@LaunchActivity, "Can't find node-webkit.html or ccloader/index.html in OBB", Toast.LENGTH_LONG).show()

            instanceNotFound()
        }else{
            gameFilesIntact()
            return
        }
    }

    private fun gameFilesIntact(){
        Log.i("CrossCode", "Instance directory found!")

        v(R.id.mounting_progress_bar).visibility = GONE
        v(R.id.launch_button).isEnabled = true
    }

    private fun instanceNotFound(){
        userConfirmedCCLoader = false
        userConfirmedCopiedInst = false
        userConfirmedDownloadedTools = false
        userConfirmedPackagedGameFiles = false

        showCurrentGuide(false)
    }

    private fun showCurrentGuide(showTransition: Boolean){
        Log.i("CrossCode", "Showing user guide on installing the instance directory.")

        val setupContainer: View
        if(findViewById<ScrollView>(R.id.setup_container) == null){
            setupContainer = findViewById<ViewStub>(R.id.setup_container_stub).inflate()
        }else{
            setupContainer = findViewById<ScrollView>(R.id.setup_container)
        }

        v(R.id.launch_container).visibility = GONE

        var pastStepsSuccessfull = true

        val hasStoragePermission = hasStoragePermission()

        if(hasStoragePermission){
            v(R.id.permission_text_tv).visibility = GONE
            v(R.id.ask_permission_btn).visibility = GONE
        }else{
            v(R.id.permission_text_tv).visibility = VISIBLE
            v(R.id.ask_permission_btn).visibility = VISIBLE

            v(R.id.ask_permission_btn).setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 0)
                }else{
                    Toast.makeText(this, "Can't request permission. Please contact the dev.", Toast.LENGTH_LONG).show()
                }
            }
        }
        pastStepsSuccessfull = pastStepsSuccessfull && hasStoragePermission

        if(pastStepsSuccessfull && userConfirmedCCLoader){
            v(R.id.install_ccloader_text_tv).visibility = GONE
            v(R.id.install_ccloader_btn).visibility = GONE
        }else if(pastStepsSuccessfull){
            v(R.id.install_ccloader_text_tv).visibility = VISIBLE
            v(R.id.install_ccloader_btn).visibility = VISIBLE

            v(R.id.install_ccloader_btn).setOnClickListener {
                userConfirmedCCLoader = true
                showCurrentGuide(true)
            }
        }
        pastStepsSuccessfull = pastStepsSuccessfull && userConfirmedCCLoader

        if(pastStepsSuccessfull && userConfirmedCopiedInst){
            v(R.id.copy_instance_text_tv).visibility = GONE
            v(R.id.copy_instance_btn).visibility = GONE
        }else if(pastStepsSuccessfull){
            v(R.id.copy_instance_text_tv).visibility = VISIBLE
            v(R.id.copy_instance_btn).visibility = VISIBLE

            v(R.id.copy_instance_btn).setOnClickListener {
                userConfirmedCopiedInst = true

                setupContainer.visibility = GONE
                v(R.id.launch_container).visibility = VISIBLE
                handler.post {
                    tryToAccessInstance()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_DENIED)){
            Toast.makeText(this, "CrossAndroid unfortunately can not function without this permission.", Toast.LENGTH_LONG).show()
        }

        showCurrentGuide(false)
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchGameActivity(){
        val intent = Intent(this, GameActivity::class.java)
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    private fun launchPreferencesActivity(){
        val intent = Intent(this, PreferencesActivity::class.java)
        startActivity(intent)
    }
}
