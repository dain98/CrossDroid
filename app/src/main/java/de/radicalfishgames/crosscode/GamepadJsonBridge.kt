package de.radicalfishgames.crosscode

import org.json.JSONArray
import org.json.JSONObject


class GamepadJsonBridge(private val onControlUpdate: () -> Unit = {}){

    // Actual data representation in JSON
    private val jsonAxes = JSONArray()
    private val jsonButtons = JSONArray()
    private val jsonGamepad = JSONObject().apply {
        put("id", "Virtual Controller")
        put("index", 0)
        put("connected", true)
        put("timestamp", 0)
        put("mapping", "standard")
        put("axes", jsonAxes)
        put("buttons", jsonButtons)
    }

    val asJsonString: String
        get() {
            return jsonGamepad.toString()
        }

    // This is basically just a convenience interface for setting the JSON states
    // Mapping layout from https://w3c.github.io/gamepad/standard_gamepad.svg
    val leftStick = Stick(0, 1, 10)
    val rightStick = Stick(2, 3,  11)

    val leftCross = ButtonCross(12, 13, 14, 15)
    val rightCross = ButtonCross(3, 0, 2, 1)

    val leftBumper = Button(4)
    val rightBumper = Button(5)

    val leftTrigger = Button(6)
    val rightTrigger = Button(7)

    val selectOrBack = Button(8)
    val startOrForward = Button(9)
    val center = Button(16)

    inner class ButtonCross(
        topJsonIndex: Int,
        bottomJsonIndex: Int,
        leftJsonIndex: Int,
        rightJsonIndex: Int
    ){
        val top: Button = Button(topJsonIndex)
        val bottom: Button = Button(bottomJsonIndex)
        val left: Button = Button(leftJsonIndex)
        val right: Button = Button(rightJsonIndex)
    }

    inner class Stick(
        horizontalJsonIndex: Int,
        verticalJsonIndex: Int,
        buttonJsonIndex: Int
    ){

        // negative = left; positive = right
        val horizontal: Axis = Axis(horizontalJsonIndex)
        // negative = up; positive = down
        val vertical: Axis = Axis(verticalJsonIndex)
        val button: Button = Button(buttonJsonIndex)

    }

    inner class Button(
        private val jsonIndex: Int
    ){

        var pressed: Boolean
            get() {
                return jsonButtons[jsonIndex] == 1.0
            }

            set(value) {
                val jsonValue = if(value){
                    1.0
                }else{
                    0.0
                }
                jsonButtons.put(jsonIndex, jsonValue)

                onControlUpdate()
            }

        init {
            pressed = false
        }
    }

    inner class Axis(
        private val jsonIndex: Int
    ){
        var state: Double
            get() {
                return jsonAxes[jsonIndex] as Double
            }
            set(value) {
                val actualValue: Double = when {
                    value > 1.0 -> 1.0
                    value < -1.0 -> -1.0
                    else -> value
                }

                jsonAxes.put(jsonIndex, actualValue)
                onControlUpdate()
            }

        init {
            state = 0.0
        }
    }

}
