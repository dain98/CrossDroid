package de.radicalfishgames.crosscode.gamelisteners

import android.os.Handler
import android.util.Log
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import de.radicalfishgames.crosscode.features.Feature
import java.util.*
import kotlin.collections.HashSet


class GameEventManager(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {
    
    private val listeners = listOf(
        MenuFullEnter(this),
        MenuExit(this),
        TradeMenu(this),
        GameStateChangeCutscene(this),
        MessageRing(this),
        MessageEnd(this),
        ElementModeChange(this),
        ElementLoad(this)
    )

    private val onMenuEnter: MutableSet<() -> Unit> = HashSet()
    private val onMenuExit: MutableSet<() -> Unit> = HashSet()

    private val onCutsceneEnter: MutableSet<() -> Unit> = HashSet()
    private val onCutsceneExit: MutableSet<() -> Unit> = HashSet()

    private val onDirectLinkRing: MutableSet<() -> Unit> = HashSet()
    private val onDirectLinkEnd: MutableSet<() -> Unit> = HashSet()

    private val onElementChange: MutableSet<(element: ElementMode) -> Unit> = HashSet()

    // Post events to the original thread instead of staying in the JS one, so
    // the event handlers can modify the View
    private val handler = Handler()

    override fun onPreGamePageLoad() {
        for(listener in listeners){
            gameWrapper.exposeJSInterface(listener, listener.interfaceName)
        }
    }

    override fun onPostGamePageLoad() {

        Log.d("CrossCode", "Injecting JS listeners...")

        var requiredModulesJS = ""
        val modulesAddedAlready = LinkedList<String>()

        var observerRegistrationJS = ""
        val observerObjectsAddedAlready = LinkedList<String>()

        var eventIfsJS = ""

        for(listener in listeners){

            if(!modulesAddedAlready.contains(listener.requiredModuleName)){
                modulesAddedAlready.add(listener.requiredModuleName)

                requiredModulesJS += ".requires(\"${listener.requiredModuleName}\")"
            }

            if(!observerObjectsAddedAlready.contains(listener.eventObjectName)){
                observerObjectsAddedAlready.add(listener.eventObjectName)

                observerRegistrationJS += "sc.Model.addObserver(sc.${listener.eventObjectName}, this);"
            }

            eventIfsJS +=
                """
                    if(origin === sc.${listener.eventObjectName} && eventNumber === sc.${listener.eventType}) {
                        ${listener.onEventJS} 
                    }
                """
        }

        var jsForAllListeners =
            """
            ig.module("crossandroid.events.listeners")$requiredModulesJS.defines(function(){
                sc.CrossAndroidEventListener = ig.GameAddon.extend({
                    init: function() {
                        $observerRegistrationJS
                    },
                    
                    modelChanged(origin, eventNumber, optionalArgs){
                        $eventIfsJS
                    }
                });
                ig.addGameAddon(function() {
                    return sc.crossAndroidEventListener = new sc.CrossAndroidEventListener
                })
            })
        """

        jsForAllListeners = jsForAllListeners
            .trimIndent()
            .replace("\n", "")

        runJS(jsForAllListeners)
    }

    fun onMenuEnter(run: () -> Unit) = onMenuEnter.add(run)

    internal fun onMenuEnter(){
        handler.post {
            onMenuEnter.forEach {
                it.invoke()
            }
        }
    }

    fun onMenuExit(run: () -> Unit) = onMenuExit.add(run)

    internal fun onMenuExit(){
        handler.post {
            onMenuExit.forEach {
                it.invoke()
            }
        }
    }

    fun onCutsceneEnter(run: () -> Unit) = onCutsceneEnter.add(run)

    internal fun onCutsceneEnter(){
        handler.post {
            onCutsceneEnter.forEach {
                it.invoke()
            }
        }
    }

    fun onCutsceneExit(run: () -> Unit) = onCutsceneExit.add(run)

    internal fun onCutsceneExit(){
        handler.post {
            onCutsceneExit.forEach {
                it.invoke()
            }
        }
    }

    fun onDirectLinkRing(run: () -> Unit) = onDirectLinkRing.add(run)

    internal fun onDirectLinkRing(){
        handler.post {
            onDirectLinkRing.forEach {
                it.invoke()
            }
        }
    }

    fun onDirectLinkEnd(run: () -> Unit) = onDirectLinkEnd.add(run)

    internal fun onDirectLinkEnd(){
        handler.post {
            onDirectLinkEnd.forEach {
                it.invoke()
            }
        }
    }

    fun onElementChange(run: (element: ElementMode) -> Unit) = onElementChange.add(run)

    internal fun onElementModeChange(elementMode: ElementMode){
        handler.post {
            onElementChange.forEach {
                it.invoke(elementMode)
            }
        }
    }
}