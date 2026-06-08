package de.radicalfishgames.crosscode.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import androidx.core.util.contains
import androidx.core.util.containsKey
import androidx.core.util.keyIterator
import androidx.core.util.set
import de.radicalfishgames.crosscode.R


open class ControlView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val onTouch: MutableSet<() -> Unit> = HashSet()
    private val onMove: MutableSet<(x: Float, y: Float) -> Unit> = HashSet()
    private val onRelease: MutableSet<() -> Unit> = HashSet()

    private val imagePaint = Paint().apply {
        isDither = false
        isAntiAlias = false
        isFilterBitmap = false
    }


    private lateinit var fullSizeRect: RectF
    var imageBitmap: Bitmap

    init {
        context.theme.obtainStyledAttributes(attrs, R.styleable.ControlView, 0, 0).apply {

            try {
                val resId = getResourceId(R.styleable.ControlView_image, -1)
                val options = BitmapFactory.Options()
                options.inScaled = false
                imageBitmap = BitmapFactory.decodeResource(context.resources, resId, options)
            } finally {
                recycle()
            }
        }

    }

    private val activePointers = SparseArray<PointF>()
    private var mainPointerId: Int? = null

    override fun onDraw(canvas: Canvas) {

        canvas.apply {

            drawBitmap(
                imageBitmap,
                null,
                fullSizeRect,
                imagePaint
            )
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)

        fullSizeRect = RectF(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            width.toFloat() - paddingRight,
            height.toFloat() - paddingBottom
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        // Update pointer list
        when(event.action){
            // New finger placed on screen
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointer = PointF()

                pointer.x = event.getX(pointerIndex)
                pointer.y = event.getY(pointerIndex)

                activePointers[pointerId] = pointer
            }

            // Finger(s) moved
            MotionEvent.ACTION_MOVE -> {
                for(index in 0 until event.pointerCount){
                    val movedId = event.getPointerId(index)

                    // A pointer moved into our View from another View
                    if(!activePointers.contains(movedId)){
                        activePointers[movedId] = PointF()
                    }

                    val pointer = activePointers[movedId]

                    pointer.x = event.getX(index)
                    pointer.y = event.getY(index)

                }
            }

            // Finger lifted (or another View has taken over control of the event)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                activePointers.remove(pointerId)
            }
        }

        // Check if main pointer still exists, is inside the view, etc.
        val hadOldMainPointer = mainPointerId != null

        val mainPointerStillValid = hadOldMainPointer
                && activePointers.containsKey(mainPointerId!!)
                && isWithinView(
                    activePointers[mainPointerId!!].x,
                    activePointers[mainPointerId!!].y
                )

        if(!mainPointerStillValid){
            mainPointerId = null

            //Search for a new pointer within the view
            for(id in activePointers.keyIterator()){
                val pointer = activePointers[id]

                if(isWithinView(pointer.x, pointer.y)){
                    mainPointerId = id
                    break
                }
            }

            // A fresh pointer has been selected
            if(mainPointerId != null && !hadOldMainPointer) {
                onTouch()

                onMove()

                // A new pointer has been selected, but this is not an unpressed button - move
            }else if(mainPointerId != null && hadOldMainPointer){
                onMove()

                // Last pointer moved out of the view, release
            }else if(hadOldMainPointer){
                onRelease()
            }

            // All is fine with our main pointer, update its position
        }else{
            onMove()

        }

        // Return true in any case so we get notified when the pointer enters our view
        return true
    }

    protected open fun isWithinView(x: Float, y: Float): Boolean {
        return true
    }

    fun onTouch(run: () -> Unit) = onTouch.add(run)

    private fun onTouch(){
        onTouch.forEach { it.invoke() }
    }

    fun onMove(run: (x: Float, y: Float) -> Unit) = onMove.add(run)

    private fun onMove(){
        onMove.forEach { it.invoke(
            activePointers[mainPointerId!!].x,
            activePointers[mainPointerId!!].y
        ) }
    }

    fun onRelease(run: () -> Unit) = onRelease.add(run)

    private fun onRelease(){
        onRelease.forEach { it.invoke() }
    }
}