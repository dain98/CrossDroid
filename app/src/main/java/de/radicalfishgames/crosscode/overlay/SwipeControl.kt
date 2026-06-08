package de.radicalfishgames.crosscode.overlay

import android.content.Context
import android.util.AttributeSet
import kotlin.math.abs


class SwipeControl(context: Context, attrs: AttributeSet) : ControlView(context, attrs) {

    private var onSwipe: MutableSet<(right: Boolean, left: Boolean, up: Boolean, down: Boolean) -> Unit> = HashSet()

    private var downX: Float? = null
    private var downY: Float? = null

    private var latestX: Float? = null
    private var latestY: Float? = null

    init {
        onMove { x: Float, y: Float ->
            if(downX == null || downY == null){
                downX = x
                downY = y

            }else{

                latestX = x
                latestY = y
            }
        }

        onRelease {

            if(latestX != null && latestY != null && downX != null && downY != null){
                val xDiff = latestX!! - downX!!
                val yDiff = latestY!! - downY!!

                val right = isSwipeRight(xDiff, yDiff)
                val left = isSwipeLeft(xDiff, yDiff)
                val up = isSwipeUp(xDiff, yDiff)
                val down = isSwipeDown(xDiff, yDiff)

                if(right || left || up || down){
                    onSwipe(right, left, up, down)
                }
            }

            downX = null
            downY = null
            latestX = null
            latestY = null
        }
    }


    private fun isSwipeRight(xDiff: Float, yDiff: Float): Boolean {
        return (abs(yDiff) / abs(xDiff)) < RATIO_MAX && xDiff > 0
    }

    private fun isSwipeLeft(xDiff: Float, yDiff: Float): Boolean {
        return (abs(yDiff) / abs(xDiff)) < RATIO_MAX && xDiff < 0
    }

    private fun isSwipeUp(xDiff: Float, yDiff: Float): Boolean {
        return (abs(xDiff) / abs(yDiff)) < RATIO_MAX && yDiff < 0
    }

    private fun isSwipeDown(xDiff: Float, yDiff: Float): Boolean {
        return (abs(xDiff) / abs(yDiff)) < RATIO_MAX && yDiff > 0
    }

    fun onSwipe(run: (right: Boolean, left: Boolean, up: Boolean, down: Boolean) -> Unit) = onSwipe.add(run)

    private fun onSwipe(right: Boolean, left: Boolean, up: Boolean, down: Boolean){
        onSwipe.forEach {
            it.invoke(right, left, up, down)
        }
    }

    companion object {
        const val RATIO_MAX = 0.8
    }
}