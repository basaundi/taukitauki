package com.example.flickkeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

class FlickKeyboardView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val rows = 4
    private val cols = 5
    private var cellWidth = 0f
    private var cellHeight = 0f
    
    var isDarkMode = true 
    var layoutMap: Map<Pair<Int, Int>, FlickKey> = emptyMap()
    var modeLabel = "abc"
    var isUppercase = false

    private var pressedRow = -1
    private var pressedCol = -1
    private val paintHighlight = Paint().apply { style = Paint.Style.FILL }

    private val paintGrid = Paint().apply { strokeWidth = 2f; style = Paint.Style.STROKE }
    private val paintCenterText = Paint().apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val paintFlickText = Paint().apply { textAlign = Paint.Align.CENTER; textSize = 26f }

    var keyActionListener: ((row: Int, col: Int, direction: FlickDirection) -> Unit)? = null
    var longPressListener: ((row: Int, col: Int) -> Unit)? = null
    var backspaceListener: (() -> Unit)? = null

    // Handlers for repeating backspace
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            backspaceListener?.invoke()
            repeatHandler.postDelayed(this, 50) // Repeat every 50ms
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val c = (e.x / cellWidth).toInt().coerceIn(0, cols - 1)
            val r = (e.y / cellHeight).toInt().coerceIn(0, rows - 1)
            longPressListener?.invoke(r, c)
        }
    })

    private var startX = 0f; private var startY = 0f
    private var activeRow = -1; private var activeCol = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellWidth = w.toFloat() / cols
        cellHeight = h.toFloat() / rows
        paintCenterText.textSize = cellHeight * 0.3f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bgColor = if (isDarkMode) Color.BLACK else Color.WHITE
        canvas.drawColor(bgColor)

        paintGrid.color = if (isDarkMode) 0xFF333333.toInt() else 0xFFCCCCCC.toInt()
        paintCenterText.color = if (isDarkMode) Color.WHITE else Color.BLACK
        paintFlickText.color = if (isDarkMode) Color.LTGRAY else Color.DKGRAY
	paintHighlight.color = if (isDarkMode) 0xFF444444.toInt() else 0xFFDDDDDD.toInt()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * cellWidth
                val top = r * cellHeight
		if(r == pressedRow && c == pressedCol){
                    canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintHighlight)
	        }else{
                    canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintGrid)
	        }

                val specialLabel = when {
                    r == 3 && c == 0 -> modeLabel
                    r == 0 && c == 4 -> "⌫"
                    r == 1 && c == 0 -> "←"
                    r == 1 && c == 4 -> "→"
                    r == 2 && c == 4 -> "␣"
                    r == 3 && c == 4 -> "⏎"
                    else -> null
                }

                if (specialLabel != null) {
                    canvas.drawText(specialLabel, left + cellWidth / 2, top + cellHeight / 2 + 15, paintCenterText)
                } else {
                    layoutMap[Pair(r, c)]?.let { drawKeyLabels(canvas, it, left, top) }
                }
            }
        }
    }

    private fun drawKeyLabels(canvas: Canvas, key: FlickKey, left: Float, top: Float) {
        val cx = left + cellWidth / 2
        val cy = top + cellHeight / 2
        val pad = 30f

        fun formatText(text: String?): String? {
            if (text == null) return null
            return if (isUppercase && text.length == 1 && text[0].isLetter()) text.uppercase() else text
        }

        formatText(key.tap)?.let { canvas.drawText(it, cx, cy + 15, paintCenterText) }
        formatText(key.up)?.let { canvas.drawText(it, cx, top + pad, paintFlickText) }
        formatText(key.down)?.let { canvas.drawText(it, cx, top + cellHeight - 15, paintFlickText) }
        formatText(key.left)?.let { canvas.drawText(it, left + 25, cy + 10, paintFlickText) }
        formatText(key.right)?.let { canvas.drawText(it, left + cellWidth - 25, cy + 10, paintFlickText) }
        
        formatText(key.ul)?.let { canvas.drawText(it, left + 30, top + pad + 10, paintFlickText) }
        formatText(key.ur)?.let { canvas.drawText(it, left + cellWidth - 30, top + pad + 10, paintFlickText) }
        formatText(key.dl)?.let { canvas.drawText(it, left + 30, top + cellHeight - 20, paintFlickText) }
        formatText(key.dr)?.let { canvas.drawText(it, left + cellWidth - 30, top + cellHeight - 20, paintFlickText) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                activeCol = (startX / cellWidth).toInt().coerceIn(0, cols - 1)
                activeRow = (startY / cellHeight).toInt().coerceIn(0, rows - 1)

		pressedRow = activeRow
		pressedCol = activeCol
		invalidate()

                if (activeRow == 0 && activeCol == 4) {
                    backspaceListener?.invoke() // Single delete immediately
                    repeatHandler.postDelayed(repeatRunnable, 400) // Start holding delete
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)

		pressedRow = -1
		pressedCol = -1
		invalidate()
                
                // If it was backspace, we already handled it on DOWN/Repeat. Ignore UP.
                if (!(activeRow == 0 && activeCol == 4) && activeRow != -1) {
                    val dist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    val key = layoutMap[Pair(activeRow, activeCol)]
                    val is8Way = key?.is8Way ?: false
                    
                    val dir = if (dist < 50f) FlickDirection.TAP else calculateDir(startX, startY, event.x, event.y, is8Way)
                    keyActionListener?.invoke(activeRow, activeCol, dir)
                }
                activeRow = -1
            }
        }
        return true
    }

    private fun calculateDir(x1: Float, y1: Float, x2: Float, y2: Float, is8Way: Boolean): FlickDirection {
        var angle = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()))
        if (angle < 0) angle += 360

        return if (is8Way) {
            when (angle) {
                in 337.5..360.0, in 0.0..22.5 -> FlickDirection.RIGHT
                in 22.5..67.5 -> FlickDirection.DOWN_RIGHT
                in 67.5..112.5 -> FlickDirection.DOWN
                in 112.5..157.5 -> FlickDirection.DOWN_LEFT
                in 157.5..202.5 -> FlickDirection.LEFT
                in 202.5..247.5 -> FlickDirection.UP_LEFT
                in 247.5..292.5 -> FlickDirection.UP
                else -> FlickDirection.UP_RIGHT
            }
        } else {
            // Perfect 90-degree quadrant slices for 4-way keys
            when (angle) {
                in 315.0..360.0, in 0.0..45.0 -> FlickDirection.RIGHT
                in 45.0..135.0 -> FlickDirection.DOWN
                in 135.0..225.0 -> FlickDirection.LEFT
                else -> FlickDirection.UP
            }
        }
    }
}
