package de.radicalfishgames.crosscode.features

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.webkit.JavascriptInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException


class ImportExportFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val saveStringToImport = hostActivity.preferences.getString("save_to_import", "")
    private val exportSaveEnabled = hostActivity.preferences.getBoolean("export_save", false)

    private var saveStringToExport: String? = null

    override fun onPreGamePageLoad() {

        gameWrapper.exposeJSInterface(this, "CAImportExport")

    }

    override fun onPostGamePageLoad() {

        val importJS: String
        if(saveStringToImport != null && saveStringToImport.isNotEmpty()){
            importJS = "ig.storage.pushSlotData(\"${saveStringToImport.trim()}\");"

            hostActivity.preferences.edit()
                .putString("save_to_import", "")
                .apply()

        }else{
            importJS = ""
        }

        val exportJS: String
        if(exportSaveEnabled){
            exportJS = "CAImportExport.showExportedSave(ig.storage.getLastSlotData());"
        }else{
            exportJS = ""
        }

        runJS("""
            ig.module("crossandroid.importexport").requires("impact.feature.storage.storage").defines(function(){
                sc.CrossAndroidImportExport = ig.GameAddon.extend({
                    init: function() {
                        $exportJS
                        $importJS
                    }
                });
                ig.addGameAddon(function() {
                    return sc.crossAndroidImportExport = new sc.CrossAndroidImportExport
                })
            })
        """)

    }

    @JavascriptInterface
    fun showExportedSave(saveString: String){
        try {

            MaterialAlertDialogBuilder(hostActivity)
                .setTitle("Exported Save String")
                .setMessage("Your save string is ready! You can save it as a text file now.")
                .setPositiveButton("Save") { dialog: DialogInterface, which: Int ->

                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "crosscode_savestring.txt")
                    }

                    saveStringToExport = saveString

                    hostActivity.startActivityForResult(intent, SAVE_SAVESTRING)
                }
                .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
                    hostActivity.goIntoFullScreen()
                }
                .setOnDismissListener {
                    hostActivity.goIntoFullScreen()
                }
                .show()

        }catch (ex: Exception){
            hostActivity.errorHandler.uncaughtException(Thread.currentThread(), ex)
            throw ex
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode == SAVE_SAVESTRING && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                try {
                    hostActivity.contentResolver.openFileDescriptor(uri, "w")?.use {
                        FileOutputStream(it.fileDescriptor).use { stream ->
                            stream.write(saveStringToExport?.toByteArray())
                        }
                    }
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }
        }

        if(requestCode == SAVE_SAVESTRING) {
            saveStringToExport = null
            hostActivity.goIntoFullScreen()
        }
    }

    companion object {
        const val SAVE_SAVESTRING = 1
    }
}