package de.radicalfishgames.crosscode.gamelisteners

import android.webkit.JavascriptInterface


class GameStateChangeCutscene(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CACutsceneListener"

    override val requiredModuleName: String = "game.feature.model.game-model"
    override val eventObjectName: String = "model"
    override val eventType: String = "GAME_MODEL_MSG.STATE_CHANGED"

    override val onEventJS: String =
        """
            if(origin.isCutscene()){
                $interfaceName.reportCutsceneEnter();
            }else{
                $interfaceName.reportCutsceneExit();
            }
        """

    @JavascriptInterface
    fun reportCutsceneEnter(){
        eventManager.onCutsceneEnter()

        isInCutscene = true
    }

    @JavascriptInterface
    fun reportCutsceneExit(){
        if(isInCutscene){
            eventManager.onCutsceneExit()
        }

        isInCutscene = false
    }

    companion object {
        var isInCutscene = false
    }

}

class MessageRing(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CAMessageRingListener"

    override val requiredModuleName: String = "game.feature.msg.message-model"
    override val eventObjectName: String = "message"
    override val eventType: String = "MESSAGE_EVENT.RING_PRIVATE"

    override val onEventJS: String =
        """
            $interfaceName.reportRing();
        """

    @JavascriptInterface
    fun reportRing(){
        // Report a message ring
        eventManager.onDirectLinkRing()
    }

}

class MessageEnd(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CAMessageEndListener"

    override val requiredModuleName: String = "game.feature.msg.message-model"
    override val eventObjectName: String = "message"
    override val eventType: String = "MESSAGE_EVENT.END_PRIVATE"

    override val onEventJS: String =
        """
            $interfaceName.reportMessageEnd();
        """

    @JavascriptInterface
    fun reportMessageEnd(){
        // Report a message end
        eventManager.onDirectLinkEnd()
    }

}