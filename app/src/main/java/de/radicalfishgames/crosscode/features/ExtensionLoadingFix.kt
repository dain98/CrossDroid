package de.radicalfishgames.crosscode.features

import android.util.Log
import android.webkit.JavascriptInterface
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import org.json.JSONArray
import java.io.File

class ExtensionLoadingFix(gameWrapper: GameWrapper,
                          hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    lateinit var extensionList: List<String>

    override fun onPreGamePageLoad() {
        loadExtensionList()
        gameWrapper.exposeJSInterface(this, "CrossAndroidExtensionListProvider")
    }

    override fun onPostGamePageLoad() {
        runJS(
            """
                ig.ExtensionList.inject({
                    loadInternal() {
                        setTimeout(this.loadExtensionsAndroid.bind(this), 0);
                    },
                    loadExtensionsAndroid() {
                        this.onExtensionListLoaded(${getExtensionListAsJson()});
                    }
                });
            """
        )
    }

    private fun loadExtensionList() {
        val extensionDir = File("${gameWrapper.gameDir}/assets/extension/")

        if(!extensionDir.exists() || !extensionDir.isDirectory) {
            Log.w("CrossCode", "No extension directory found! Searched at ${extensionDir.path}")
            return
        }

        val discoveredExtensions = mutableListOf<String>()

        for(singleExtDir in extensionDir.listFiles()!!) {
            if(!singleExtDir.isDirectory) {
                continue
            }

            val extensionName = singleExtDir.name

            val manifestFileRelPath = "$singleExtDir/$extensionName.json"
            val manifestFile = File(manifestFileRelPath)
            if(!manifestFile.exists()) {
                Log.w("CrossCode", "Found extension directory $extensionName, but not $manifestFileRelPath!")
                continue
            }

            Log.d("CrossCode", "Found extension $extensionName")

            discoveredExtensions.add(extensionName)
        }

        extensionList = discoveredExtensions
    }

    @JavascriptInterface
    fun getExtensionListAsJson(): String {
        val json = JSONArray()
        for(name in extensionList){
            json.put(name)
        }
        return json.toString()
    }

}