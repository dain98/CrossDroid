package de.radicalfishgames.crosscode.gamelisteners

import android.webkit.JavascriptInterface


class ElementModeChange(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CAElementModeChangeListener"

    override val requiredModuleName: String = "game.feature.player.player-model"
    override val eventObjectName: String = "model.player"
    override val eventType: String = "PLAYER_MSG.ELEMENT_MODE_CHANGE"

    override val onEventJS: String =
        """
            $interfaceName.reportElementalModeChanged(origin.currentElementMode);
        """

    @JavascriptInterface
    fun reportElementalModeChanged(element: Int){
        eventManager.onElementModeChange(ElementMode.valueOf(element))
    }

}

class ElementLoad(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CAElementLoadListener"

    override val requiredModuleName: String = "game.feature.player.player-model"
    override val eventObjectName: String = "model.player"
    override val eventType: String = "PLAYER_MSG.CONFIG_CHANGED"

    override val onEventJS: String =
        """
            $interfaceName.reportElementLoad(sc.$eventObjectName.currentElementMode);
        """

    @JavascriptInterface
    fun reportElementLoad(element: Int){
        eventManager.onElementModeChange(ElementMode.valueOf(element))
    }

}

enum class ElementMode {
    NEUTRAL,
    HEAT,
    COLD,
    SHOCK,
    WAVE;

    companion object {
        fun valueOf(index: Int): ElementMode{
            return when(index){
                0 -> NEUTRAL
                1 -> HEAT
                2 -> COLD
                3 -> SHOCK
                4 -> WAVE
                else -> NEUTRAL
            }
        }
    }
}