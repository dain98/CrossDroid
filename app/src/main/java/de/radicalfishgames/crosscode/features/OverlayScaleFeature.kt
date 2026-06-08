package de.radicalfishgames.crosscode.features

import androidx.core.view.forEach
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import kotlin.math.ceil


class OverlayScaleFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    // Because of how the scale preference seekbar works, the saved values are between 0 and 15,
    // but we want scale factors between 0.5 and 2.0
    private val scaleFactor = (hostActivity.preferences.getInt("overlay_scale_factor", 5) + 5)/10F
    private val enabled = scaleFactor != 1.0F

    override fun onPostGamePageLoad() {
        val virtualControllerFeature = gameWrapper.getFeature(VirtualControllerFeature::class)

        if(!virtualControllerFeature.enabled || !enabled){
            return
        }

        virtualControllerFeature.layoutViews.values.forEach { layoutViewGroup ->
            layoutViewGroup.forEach { view ->
                val layoutParams = view.layoutParams
                layoutParams.width = ceil(layoutParams.width * scaleFactor).toInt()
                layoutParams.height = ceil(layoutParams.height * scaleFactor).toInt()
                view.layoutParams = layoutParams
            }
            layoutViewGroup.invalidate()
        }
    }
}