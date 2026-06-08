package de.radicalfishgames.crosscode.features

import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.net.toFile
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import org.json.JSONArray
import java.io.File

class ModListProvider(gameWrapper: GameWrapper,
                      hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    lateinit var modList: List<String>

    override fun onPreGamePageLoad() {
        loadModList()
        gameWrapper.exposeJSInterface(this, "CrossAndroidModListProvider")
    }

    private fun loadModList() {
        if(!gameWrapper.modLoaderPresent){
            modList = emptyList()
        }

        val modsDir = File("${gameWrapper.gameDir}/assets/mods")

        if(!modsDir.exists() || !modsDir.isDirectory) {
            Log.w("CrossCode", "No mods directory found! Searched at ${modsDir.path}")
            return
        }

        val installedMods = mutableListOf<String>()

        for(modFile in modsDir.listFiles()!!) {
            var modFileName = modFile.name
            if(modFile.isDirectory){
                modFileName += "/"
            }

            Log.d("CrossCode", "Found mod $modFileName")

            installedMods.add(modFileName)
        }

        modList = installedMods
    }

    @JavascriptInterface
    fun getModListAsJson(): String {
        val json = JSONArray()
        for(name in modList){
            json.put(name)
        }
        return json.toString()
    }

}