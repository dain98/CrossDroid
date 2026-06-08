package de.radicalfishgames.crosscode.layouts

import android.graphics.BitmapFactory
import android.os.Handler
import android.view.View
import android.webkit.ValueCallback
import de.radicalfishgames.crosscode.GamepadJsonBridge
import de.radicalfishgames.crosscode.R
import de.radicalfishgames.crosscode.databinding.CombatLayoutBinding
import de.radicalfishgames.crosscode.features.VirtualControllerFeature
import de.radicalfishgames.crosscode.gamelisteners.ElementMode
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import kotlin.math.min
import kotlin.math.pow

object CombatLayout : VirtualControllerLayout() {

    override val layoutResId = R.layout.combat_layout

    var alwaysSprint = true
    var dashOnlyOnTap = false

    private var currentElement = ElementMode.NEUTRAL

    // Dash states
    private const val NONE = 0
    private const val DASHING = 1
    private const val DASHING_MOVE_END = 2
    private const val DASHED = 3

    private var dashState = NONE
    private var interactingWithSth = false

    override fun bindControls(gamepad: GamepadJsonBridge, layoutView: View, feature: VirtualControllerFeature, eventManager: GameEventManager){

        val b = CombatLayoutBinding.bind(layoutView)

        b.overlayToggle.onSwipe { right, left, up, down ->
            // Do in handler to avoid a crash due to the potential layout changes
            handler.post {

                if(right || left){
                    feature.switchToLayout(MenuLayout)

                }else if(up || down){
                    DirectLayout.layoutToEnable = CombatLayout
                    feature.switchToLayout(DirectLayout)
                }
            }
        }

        bindSimpleButton(b.shieldButton, gamepad.leftBumper)
        bindSimpleButton(b.dashButton, gamepad.leftBumper)
        bindSimpleButton(b.quickmenuButton, gamepad.leftTrigger)

        b.elementalButton.onSwipe { right, left, up, down ->

            // Switch buttons, because with our method you go back to neutral by swiping in the
            // opposite direction as before. Eg Neutral -> Heat: Swipe Down and then Heat -> Neutral: Swipe Up
            when (currentElement) {
                ElementMode.NEUTRAL -> when {
                    right -> fakeClick(gamepad.leftCross.right)
                    left -> fakeClick(gamepad.leftCross.left)
                    up -> fakeClick(gamepad.leftCross.top)
                    down -> fakeClick(gamepad.leftCross.bottom)
                }
                ElementMode.HEAT, ElementMode.COLD -> when {
                    right -> fakeClick(gamepad.leftCross.right)
                    left -> fakeClick(gamepad.leftCross.left)
                    up -> fakeClick(gamepad.leftCross.bottom)
                    down -> fakeClick(gamepad.leftCross.top)
                }
                ElementMode.SHOCK, ElementMode.WAVE -> when {
                    right -> fakeClick(gamepad.leftCross.left)
                    left -> fakeClick(gamepad.leftCross.right)
                    up -> fakeClick(gamepad.leftCross.top)
                    down -> fakeClick(gamepad.leftCross.bottom)
                }
            }
        }

        eventManager.onElementChange { elementMode ->

            currentElement = elementMode

            val elementUIImageResID = when(elementMode){
                ElementMode.NEUTRAL -> R.drawable.element_neutral
                ElementMode.HEAT -> R.drawable.element_heat
                ElementMode.COLD -> R.drawable.element_cold
                ElementMode.SHOCK -> R.drawable.element_shock
                ElementMode.WAVE -> R.drawable.element_wave
            }

            b.elementalButton.imageBitmap = BitmapFactory.decodeResource(
                b.elementalButton.context.resources,
                elementUIImageResID
            )

            b.elementalButton.invalidate()
        }

        b.movementPad.apply {

            var lastInsideInnerCircle = false
            var hasBeenInsideInnerCircle = false

            onDirectionUpdate { xDir, yDir ->

                val currentInsideInnerCircle = xDir.pow(2) + yDir.pow(2) <= 0.7.pow(2)
                hasBeenInsideInnerCircle = hasBeenInsideInnerCircle || currentInsideInnerCircle

                val xActual = when {
                    alwaysSprint && xDir > 0.15 -> 1.0
                    alwaysSprint && xDir < -0.15 -> -1.0
                    else -> min(xDir * 1.3, 1.0)
                }

                val yActual = when {
                    alwaysSprint && yDir > 0.15 -> 1.0
                    alwaysSprint && yDir < -0.15 -> -1.0
                    else -> min(yDir * 1.3, 1.0)
                }

                gamepad.leftStick.horizontal.state = xActual
                gamepad.leftStick.vertical.state = yActual

                if(dashState == NONE && !currentInsideInnerCircle && (!dashOnlyOnTap || !hasBeenInsideInnerCircle)){
                    dashState = DASHING

                    CombatLayout.handler.postDelayed({
                        gamepad.leftBumper.pressed = true

                        CombatLayout.handler.postDelayed({

                            if(dashState == DASHING_MOVE_END){
                                gamepad.leftStick.vertical.state = 0.0
                                gamepad.leftStick.horizontal.state = 0.0

                                gamepad.leftBumper.pressed = false

                                dashState = NONE

                            }else if(lastInsideInnerCircle){
                                gamepad.leftBumper.pressed = false

                                dashState = NONE

                            }else{
                                dashState = DASHED
                            }
                        }, FAKE_CLICK_TIME)
                    }, 60L)

                }

                if(currentInsideInnerCircle && dashState == DASHED){
                    gamepad.leftBumper.pressed = false

                    dashState = NONE

                }

                lastInsideInnerCircle = currentInsideInnerCircle
            }

            onRelease {

                if(dashState != DASHING){
                    gamepad.leftStick.vertical.state = 0.0
                    gamepad.leftStick.horizontal.state = 0.0

                    gamepad.leftBumper.pressed = false

                    dashState = NONE
                }  else {
                    dashState = DASHING_MOVE_END
                }

                // Confirm quickmenu selection
                if(lastInsideInnerCircle && gamepad.leftTrigger.pressed){
                    fakeClick(gamepad.rightCross.bottom)
                }

                lastInsideInnerCircle = false
                hasBeenInsideInnerCircle = false
            }

        }

        b.aimPad.apply {

            var lastInsideInnerCircle = false

            var longPressPending = false
            var combatArtPending = false
            val longPressHandler = Handler()

            onDirectionUpdate { xDir, yDir ->

                val currentInsideInnerCircle = xDir.pow(2) + yDir.pow(2) <= 0.3.pow(2)

                if(currentInsideInnerCircle){

                    val actionButtonPressed = gamepad.leftBumper.pressed
                            || dashState != NONE
                            || gamepad.rightStick.vertical.state != 0.0
                            || gamepad.rightStick.horizontal.state != 0.0

                    if(actionButtonPressed){

                        // Trigger combat arts - the delay is there to avoid triggering the art accidentally
                        if(!longPressPending){
                            longPressHandler.postDelayed({
                                val actionButtonStillPressed = gamepad.leftBumper.pressed
                                        || dashState != NONE
                                        || gamepad.rightStick.vertical.state != 0.0
                                        || gamepad.rightStick.horizontal.state != 0.0

                                if(actionButtonStillPressed){
                                    gamepad.rightTrigger.pressed = true
                                    combatArtPending = true
                                }
                            }, 100L)
                            longPressPending = true
                        }

                    }else{
                        gamepad.rightCross.bottom.pressed = true
                        gamepad.rightBumper.pressed = true

                        // Check whether the player is interacting with a pushpullable box
                        // Since this result might not be there in time before the combat
                        // art gets triggered, the button is also reset manually if there is
                        // a need.
                        feature.runJS(
                            """window.variable = ig.game.playerEntity.interactObject === null;""",
                            ValueCallback { result ->
                                interactingWithSth = !result.toBoolean()
                                if(interactingWithSth){
                                    gamepad.rightTrigger.pressed = false
                                }
                            }
                        )

                        // Trigger combat arts for melee
                        if(!longPressPending){

                            longPressHandler.postDelayed({
                                if(interactingWithSth){
                                    return@postDelayed;
                                }

                                gamepad.rightTrigger.pressed = true
                            }, 300L)
                            longPressPending = true
                        }
                    }
                }else{

                    gamepad.rightCross.bottom.pressed = false
                    gamepad.rightBumper.pressed = false

                    // Increase the results we send to the game, because it has some degree
                    // of "dead zones", which can lead to lost aim, failed combat arts and frustration
                    // with this control scheme.
                    gamepad.rightStick.horizontal.state = min(xDir * 1.2, 1.0)
                    gamepad.rightStick.vertical.state = min(yDir * 1.2, 1.0)

                    longPressHandler.removeCallbacksAndMessages(null)
                    longPressPending = false
                    gamepad.rightTrigger.pressed = false
                }

                lastInsideInnerCircle = currentInsideInnerCircle
            }

            onRelease {

                longPressHandler.removeCallbacksAndMessages(null)
                longPressPending = false
                gamepad.rightTrigger.pressed = false

                interactingWithSth = false

                if(lastInsideInnerCircle){
                    gamepad.rightCross.bottom.pressed = false
                    gamepad.rightBumper.pressed = false

                    // If we are executing a throw art, delay resetting the direction
                    // to avoid accidentally switching to a melee art
                    if(combatArtPending){
                        combatArtPending = false
                        CombatLayout.handler.postDelayed({
                            gamepad.rightStick.vertical.state = 0.0
                            gamepad.rightStick.horizontal.state = 0.0
                        }, 300L)
                    }else{
                        gamepad.rightStick.vertical.state = 0.0
                        gamepad.rightStick.horizontal.state = 0.0
                    }

                }else{
                    gamepad.rightCross.bottom.pressed = false

                    // CrossCode expects a button press to fire, so we give it one
                    fakeClick(gamepad.rightBumper) {
                        gamepad.rightStick.vertical.state = 0.0
                        gamepad.rightStick.horizontal.state = 0.0
                    }
                }
            }
        }

        b.menuButton.apply {

            var startedToPressAt: Long = 0
            onTouch {
                startedToPressAt = System.currentTimeMillis()

            }

            onRelease {

                val timeDiff = (System.currentTimeMillis() - startedToPressAt)

                // Decide based on long- or short click whether to open the inventory or menu
                val openMenu = timeDiff > (0.2 * 1000)

                // Open menu
                if(openMenu){
                    fakeClick(gamepad.startOrForward)

                    // Open inventory
                }else{
                    fakeClick(gamepad.selectOrBack)
                }
            }
        }
    }
}
