package de.radicalfishgames.crosscode.gamelisteners

import android.webkit.JavascriptInterface


class MenuFullEnter(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CAMenuListenerFullEnter"

    override val requiredModuleName: String = "game.feature.menu.menu-model"
    override val eventObjectName: String = "menu"
    override val eventType: String = "MENU_EVENT.FULL_MENU_ENTER"

    override val onEventJS: String =
        """
            $interfaceName.reportFullMenuOpen();
        """

    @JavascriptInterface
    fun reportFullMenuOpen(){
        eventManager.onMenuEnter()
    }
}

class MenuExit(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CAMenuListenerExit"

    override val requiredModuleName: String = "game.feature.menu.menu-model"
    override val eventObjectName: String = "menu"
    override val eventType: String = "MENU_EVENT.EXIT_MENU"

    override val onEventJS: String =
        """
            $interfaceName.reportMenuExit();
        """

    @JavascriptInterface
    fun reportMenuExit(){
        // The menu exit event fires when the trade menu is opened, which is why we ignore
        // menu events that happen during the trade menu.
        if(!TradeMenu.isMenuOpen){
            eventManager.onMenuExit()
        }
    }
}

class TradeMenu(eventManager: GameEventManager) : GameEventListener(eventManager) {

    override val interfaceName: String = "CATradeMenuListenerEnter"

    override val requiredModuleName: String = "game.feature.model.game-model"
    override val eventObjectName: String = "model"
    override val eventType: String = "GAME_MODEL_MSG.SUB_STATE_CHANGED"

    override val onEventJS: String =
        """
            if(origin.isOnMapMenu() || origin.isMenu()){
                $interfaceName.reportTradeMenuOpen();
            }else{
                $interfaceName.reportTradeMenuExit();
            }
        """

    @JavascriptInterface
    fun reportTradeMenuOpen(){
        eventManager.onMenuEnter()

        isMenuOpen = true
    }

    @JavascriptInterface
    fun reportTradeMenuExit(){
        if(isMenuOpen){
            eventManager.onMenuExit()
        }

        isMenuOpen = false
    }

    companion object {
        var isMenuOpen = false
    }
}