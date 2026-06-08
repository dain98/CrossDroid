package de.radicalfishgames.crosscode.layouts

import android.view.View
import de.radicalfishgames.crosscode.GamepadJsonBridge
import de.radicalfishgames.crosscode.R
import de.radicalfishgames.crosscode.databinding.MenuLayoutBinding
import de.radicalfishgames.crosscode.features.VirtualControllerFeature
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager


object MenuLayout : VirtualControllerLayout() {

    override val layoutResId = R.layout.menu_layout

    override fun bindControls(gamepad: GamepadJsonBridge, layoutView: View, feature: VirtualControllerFeature, eventManager: GameEventManager) {

        val b = MenuLayoutBinding.bind(layoutView)

        b.overlayToggle.onSwipe { right, left, up, down ->
            // Do in handler to avoid a crash due to the potential layout changes
            handler.post {

                if(right || left){
                    feature.switchToLayout(CombatLayout)

                }else if(up || down){
                    DirectLayout.layoutToEnable = MenuLayout
                    feature.switchToLayout(DirectLayout)
                }
            }
        }

        b.menuDirections.apply {
            onDirectionUpdate { xDir, yDir ->

                val xActual = when {
                    xDir > 0.15 -> 1.0
                    xDir < -0.15 -> -1.0
                    else -> 0.0
                }

                val yActual = when {
                    yDir > 0.15 -> 1.0
                    yDir < -0.15 -> -1.0
                    else -> 0.0
                }

                gamepad.leftStick.horizontal.state = xActual
                gamepad.leftStick.vertical.state = yActual
            }

            onRelease {
                gamepad.leftStick.vertical.state = 0.0
                gamepad.leftStick.horizontal.state = 0.0
            }
        }


        b.tabLeft.apply {
            var startedToPressAt: Long = 0
            onTouch {
                startedToPressAt = System.currentTimeMillis()
            }

            onRelease {

                val timeDiff = (System.currentTimeMillis() - startedToPressAt)

                // Decide based on long- or short click whether to press bumper or trigger
                val useTrigger = timeDiff > (0.2 * 1000)

                if(useTrigger){
                    fakeClick(gamepad.leftTrigger)

                }else{
                    fakeClick(gamepad.leftBumper)
                }
            }
        }
        b.tabRight.apply {
            var startedToPressAt: Long = 0
            onTouch {
                startedToPressAt = System.currentTimeMillis()
            }

            onRelease {

                val timeDiff = (System.currentTimeMillis() - startedToPressAt)

                // Decide based on long- or short click whether to press bumper or trigger
                val useTrigger = timeDiff > (0.2 * 1000)

                if(useTrigger){
                    fakeClick(gamepad.rightTrigger)

                }else{
                    fakeClick(gamepad.rightBumper)
                }
            }
        }

        bindSimpleButton(b.rightcrossRight, gamepad.rightCross.right)
        bindSimpleButton(b.rightcrossLeft, gamepad.rightCross.left)
        bindSimpleButton(b.rightcrossTop, gamepad.rightCross.top)
        bindSimpleButton(b.rightcrossBottom, gamepad.rightCross.bottom)

        bindSimpleButton(b.helpButton, gamepad.startOrForward)
    }
}
