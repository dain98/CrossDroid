package de.radicalfishgames.crosscode.features

import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import de.radicalfishgames.crosscode.GamepadJsonBridge
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import de.radicalfishgames.crosscode.layouts.CombatLayout
import de.radicalfishgames.crosscode.layouts.DirectLayout
import de.radicalfishgames.crosscode.layouts.MenuLayout
import de.radicalfishgames.crosscode.layouts.VirtualControllerLayout
import de.radicalfishgames.crosscode.R


class VirtualControllerFeature(gameWrapper: GameWrapper,
                               hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val gamepadState: GamepadJsonBridge = GamepadJsonBridge {updateGamepadJS()}

    private val forceVirtualController = hostActivity.preferences.getBoolean("force_virtual", false)
    private val combatAlwaysSprint = hostActivity.preferences.getBoolean("always_sprint", true)
    private val dashOnlyOnTap = hostActivity.preferences.getBoolean("disable_swipe_dash", false)

    private val availableLayouts = setOf(CombatLayout, MenuLayout, DirectLayout)
    lateinit var currentLayout: VirtualControllerLayout
        private set

    internal val enabled = !hostActivity.isNativeControllerActive() || forceVirtualController

    val layoutViews = HashMap<VirtualControllerLayout, ViewGroup>()

    private var isControllerReady = false


    override fun onPostGamePageLoad() {
        if(!enabled){
            return
        }

        // Hook the getGamepads-function to only return our gamepad
        runJS(
            """
                var $JS_GAMEPADS_VAR = [];
                navigator.getGamepads = function(){
                    return $JS_GAMEPADS_VAR;
                };
            """
                .trimIndent()
                .replace("\n", "")
        )

        CombatLayout.alwaysSprint = combatAlwaysSprint
        CombatLayout.dashOnlyOnTap = dashOnlyOnTap

        initializeLayouts()

        currentLayout = DirectLayout
        DirectLayout.layoutToEnable = MenuLayout
        gameWrapper.blockWebViewClicks = false
        layoutViews[DirectLayout]!!.visibility = View.VISIBLE

        isControllerReady = true
    }

    private fun initializeLayouts(){

        for(controller in availableLayouts){

            val parentView = hostActivity.layoutInflater.inflate(controller.layoutResId, hostActivity.findViewById<ViewGroup>(R.id.control_container)) as ViewGroup
            parentView.visibility = View.VISIBLE

            val view = parentView.getChildAt(parentView.childCount - 1) as ViewGroup

            // Hide all controllers by default and only show them when they are needed
            view.visibility = View.GONE
            layoutViews[controller] = view

            controller.bindControls(gamepadState, view, this, gameWrapper.getFeature(GameEventManager::class))
        }
    }

    fun switchToLayout(newLayout: VirtualControllerLayout){
        if(!enabled){
            return
        }
        Log.d("CrossCode", "Switching to virtual controller ${newLayout.javaClass.simpleName}")

        layoutViews[currentLayout]!!.visibility = View.GONE
        currentLayout = newLayout
        layoutViews[currentLayout]!!.visibility = View.VISIBLE

        gameWrapper.blockWebViewClicks = !currentLayout.allowWebViewInteraction
    }

    private fun updateGamepadJS(){
        if(!enabled){
            return
        }

        if(isControllerReady){
            val jsonGamepad = gamepadState.asJsonString

            runJS("$JS_GAMEPADS_VAR = [JSON.parse('$jsonGamepad')];")
        }
    }

    companion object {
        const val JS_GAMEPADS_VAR = "injectedGamepads"
    }
}