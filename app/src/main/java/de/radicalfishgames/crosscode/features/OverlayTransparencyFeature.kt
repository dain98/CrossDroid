package de.radicalfishgames.crosscode.features

import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper


class OverlayTransparencyFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val transparency = hostActivity.preferences.getInt("overlay_transparency", 0)

    private val enabled = transparency > 0

    override fun onPostGamePageLoad() {

        val virtualControllerFeat = gameWrapper.getFeature(VirtualControllerFeature::class)

        if(!virtualControllerFeat.enabled || !enabled){
            return
        }

        virtualControllerFeat.layoutViews.values.forEach { layoutViewGroup ->
            layoutViewGroup.alpha = 1F - (transparency / 100F)
        }
    }
}