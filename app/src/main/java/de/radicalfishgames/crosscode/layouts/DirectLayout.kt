package de.radicalfishgames.crosscode.layouts

import android.view.View
import de.radicalfishgames.crosscode.GamepadJsonBridge
import de.radicalfishgames.crosscode.R
import de.radicalfishgames.crosscode.databinding.DirectLayoutBinding
import de.radicalfishgames.crosscode.features.VirtualControllerFeature
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager


object DirectLayout : VirtualControllerLayout() {

    override val layoutResId = R.layout.direct_layout
    override val allowWebViewInteraction = true

    lateinit var layoutToEnable: VirtualControllerLayout

    override fun bindControls(
        gamepad: GamepadJsonBridge,
        layoutView: View,
        feature: VirtualControllerFeature,
        eventManager: GameEventManager
    ) {
        val b = DirectLayoutBinding.bind(layoutView)
        b.overlayToggle.onRelease {
            // Do in handler to avoid a crash due to the potential layout changes
            handler.post {
                feature.switchToLayout(layoutToEnable)
            }
        }
    }
}
