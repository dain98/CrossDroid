package de.radicalfishgames.crosscode.layouts

import android.os.Handler
import android.view.View
import de.radicalfishgames.crosscode.GamepadJsonBridge
import de.radicalfishgames.crosscode.features.VirtualControllerFeature
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import de.radicalfishgames.crosscode.overlay.ControlView


abstract class VirtualControllerLayout {

    abstract val layoutResId: Int
    open val allowWebViewInteraction = false

    protected val handler = Handler()


    abstract fun bindControls(gamepad: GamepadJsonBridge, layoutView: View, feature: VirtualControllerFeature, eventManager: GameEventManager)

    // Utility functions

    protected fun bindSimpleButton(view: ControlView, to: GamepadJsonBridge.Button){
        view.apply {
            onTouch {
                to.pressed = true
            }

            onRelease {
                to.pressed = false
            }
        }
    }

    protected fun fakeClick(button: GamepadJsonBridge.Button) = fakeClick(button) {}

    protected fun fakeClick(button: GamepadJsonBridge.Button, callback: () -> Unit){
        button.pressed = true

        handler.postDelayed({

            button.pressed = false
            callback.invoke()
        }, FAKE_CLICK_TIME)
    }

    companion object {
        const val FAKE_CLICK_TIME = 40L
    }

}