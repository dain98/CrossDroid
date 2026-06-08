package de.radicalfishgames.crosscode.features

import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import de.radicalfishgames.crosscode.layouts.CombatLayout
import de.radicalfishgames.crosscode.layouts.MenuLayout


class AutoLayoutSwitchFeature(gameWrapper: GameWrapper,
                              hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val enabled = hostActivity.preferences.getBoolean("switch_layout_automatically", true)

    override fun onPostGamePageLoad() {

        val virtualControllerFeature = gameWrapper.getFeature(VirtualControllerFeature::class)

        if(!virtualControllerFeature.enabled || !enabled){
            return
        }

        gameWrapper.getFeature(GameEventManager::class).apply {

            onMenuEnter {
                virtualControllerFeature.switchToLayout(MenuLayout)
            }

            onMenuExit {
                virtualControllerFeature.switchToLayout(CombatLayout)
            }
        }

    }
}