package com.example.flickkeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

enum class FlickDirection {
    TAP, UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
}

class FlickKeyboardView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val rows = 4
    private val cols = 5
    private var cellWidth = 0f
    private var cellHeight = 0f

    private var startX = 0f
    private var startY = 0f
    private var activeRow = -1
    private var activeCol = -1

    private val flickThreshold = 50f // pixels needed to register as a flick

    private val paintGrid = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    private val paintText = Paint().apply {
        color = Color.BLACK
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    var keyActionListener: ((row: Int, col: Int, direction: FlickDirection) -> Unit)? = null
    var modeLabel = "abc" // Used to display current mode on the bottom-left key

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellWidth = w.toFloat() / cols
        cellHeight = h.toFloat() / rows
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw 4x5 Grid
        for (i in 0..cols) canvas.drawLine(i * cellWidth, 0f, i * cellWidth, height.toFloat(), paintGrid)
        for (i in 0..rows) canvas.drawLine(0f, i * cellHeight, width.toFloat(), i * cellHeight, paintGrid)

        // Draw mode label on bottom-left key (Row 3, Col 0)
        canvas.drawText(modeLabel, cellWidth / 2, (3 * cellHeight) + (cellHeight / 2) + 15, paintText)
        
        // You can expand this to draw the central character for every key based on your layout configuration!
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                activeCol = (startX / cellWidth).toInt().coerceIn(0, cols - 1)
                activeRow = (startY / cellHeight).toInt().coerceIn(0, rows - 1)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val endX = event.x
                val endY = event.y
                val distance = hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()

                val direction = if (distance < flickThreshold) {
                    FlickDirection.TAP
                } else {
                    calculateDirection(startX, startY, endX, endY)
                }

                if (activeRow != -1 && activeCol != -1) {
                    keyActionListener?.invoke(activeRow, activeCol, direction)
                }
                activeRow = -1
                activeCol = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun calculateDirection(x1: Float, y1: Float, x2: Float, y2: Float): FlickDirection {
        val angleRad = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        var angleDeg = Math.toDegrees(angleRad)
        if (angleDeg < 0) angleDeg += 360

        return when (angleDeg) {
            in 337.5..360.0, in 0.0..22.5 -> FlickDirection.RIGHT
            in 22.5..67.5 -> FlickDirection.DOWN_RIGHT
            in 67.5..112.5 -> FlickDirection.DOWN
            in 112.5..157.5 -> FlickDirection.DOWN_LEFT
            in 157.5..202.5 -> FlickDirection.LEFT
            in 202.5..247.5 -> FlickDirection.UP_LEFT
            in 247.5..292.5 -> FlickDirection.UP
            in 292.5..337.5 -> FlickDirection.UP_RIGHT
            else -> FlickDirection.TAP
        }
    }
}
