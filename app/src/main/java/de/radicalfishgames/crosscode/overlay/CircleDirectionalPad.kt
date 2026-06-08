package de.radicalfishgames.crosscode.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import de.radicalfishgames.crosscode.R
import kotlin.math.abs
import kotlin.math.roundToInt


class CircleDirectionalPad(context: Context, attrs: AttributeSet) : CircleButton(context, attrs) {

    private val onDirectionUpdate: MutableSet<(xDirection: Double, yDirection: Double) -> Unit> = HashSet()

    init {
        onMove { xTouch: Float, yTouch: Float -> updateDirection(xTouch, yTouch) }
    }

    fun onDirectionUpdate(run: (xDirection: Double, yDirection: Double) -> Unit) = onDirectionUpdate.add(run)

    private fun updateDirection(xTouch: Float, yTouch: Float){
        val xDiff = xTouch - centerX
        val yDiff = yTouch - centerY

        // Return the values as values ranging from -1.0 to 1.0
        val xDir = xDiff / radius
        val yDir = yDiff / radius

        onDirectionUpdate.forEach { it.invoke(xDir.toDouble(), yDir.toDouble()) }
    }
}