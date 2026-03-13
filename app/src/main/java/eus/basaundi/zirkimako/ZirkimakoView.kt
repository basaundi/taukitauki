package eus.basaundi.zirkimako

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

class ZirkimakoView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val rows = 4
    private val cols = 5
    private var cellWidth = 0f
    private var cellHeight = 0f
    
    var layoutMap: Map<Pair<Int, Int>, FlickKey> = emptyMap()
    var modeLabel = context.getString(R.string.mode_lower)
    var swapLabel = context.getString(R.string.label_swap)
    var isUppercase = false
    var isShifted = false

    private var pressedRow = -1
    private var pressedCol = -1

    private val paintGrid = Paint().apply { strokeWidth = 2f; style = Paint.Style.STROKE; color = 0xFF333333.toInt() }
    private val paintHighlight = Paint().apply { style = Paint.Style.FILL; color = 0xFF444444.toInt() }
    private val paintSpecialKey = Paint().apply { style = Paint.Style.FILL; color = 0xFF222222.toInt() }
    private val paintShiftKeyActive = Paint().apply { style = Paint.Style.FILL; color = 0xFF555555.toInt() }
    private val paintEnterKey = Paint().apply { style = Paint.Style.FILL; color = 0xFF1565C0.toInt() }
    private val paintCenterText = Paint().apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Color.WHITE }
    private val paintFlickText = Paint().apply { textAlign = Paint.Align.CENTER; textSize = 26f; color = Color.LTGRAY }

    var keyActionListener: ((row: Int, col: Int, direction: FlickDirection) -> Unit)? = null
    var longPressListener: ((row: Int, col: Int) -> Unit)? = null
    var backspaceListener: (() -> Unit)? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            backspaceListener?.invoke()
            repeatHandler.postDelayed(this, 60)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellWidth = w.toFloat() / cols
        cellHeight = h.toFloat() / rows
        paintCenterText.textSize = cellHeight * 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * cellWidth
                val top = r * cellHeight
                
                // Background for special keys
                when {
                    r == 3 && c == 4 -> {
                        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintEnterKey)
                    }
                    r == 2 && c == 0 -> {
                        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, if (isShifted) paintShiftKeyActive else paintSpecialKey)
                    }
                    (r == 0 && c == 4) || (r == 1 && c == 0) || (r == 1 && c == 4) || (r == 2 && c == 4) || (r == 3 && c == 0) || (r == 3 && c == 1) -> {
                        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintSpecialKey)
                    }
                }
                
                if (r == pressedRow && c == pressedCol) {
                    canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintHighlight)
                }
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintGrid)

                val specialLabel = when {
                    r == 0 && c == 4 -> context.getString(R.string.label_backspace)
                    r == 1 && c == 0 -> context.getString(R.string.label_left)
                    r == 1 && c == 4 -> context.getString(R.string.label_right)
                    r == 2 && c == 4 -> context.getString(R.string.label_space_key)
                    r == 3 && c == 0 -> modeLabel
                    r == 3 && c == 1 -> swapLabel
                    r == 3 && c == 4 -> context.getString(R.string.label_enter)
                    else -> null
                }

                if (specialLabel != null) {
                    canvas.drawText(specialLabel, left + cellWidth / 2, top + cellHeight / 2 + (paintCenterText.textSize/3), paintCenterText)
                } else {
                    layoutMap[Pair(r, c)]?.let { drawKeyLabels(canvas, it, left, top) }
                }
            }
        }
    }

    private fun drawKeyLabels(canvas: Canvas, key: FlickKey, left: Float, top: Float) {
        val cx = left + cellWidth / 2
        val cy = top + cellHeight / 2
        
        fun format(s: String?) = when {
            s == null -> null
            isUppercase -> s.uppercase()
            isShifted -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            else -> s
        }

        format(key.tap)?.let { canvas.drawText(it, cx, cy + 15, paintCenterText) }
        format(key.up)?.let { canvas.drawText(it, cx, top + 35, paintFlickText) }
        format(key.down)?.let { canvas.drawText(it, cx, top + cellHeight - 15, paintFlickText) }
        format(key.left)?.let { canvas.drawText(it, left + 25, cy + 10, paintFlickText) }
        format(key.right)?.let { canvas.drawText(it, left + cellWidth - 25, cy + 10, paintFlickText) }
        
        format(key.ul)?.let { canvas.drawText(it, left + 30, top + 35, paintFlickText) }
        format(key.ur)?.let { canvas.drawText(it, left + cellWidth - 30, top + 35, paintFlickText) }
        format(key.dl)?.let { canvas.drawText(it, left + 30, top + cellHeight - 15, paintFlickText) }
        format(key.dr)?.let { canvas.drawText(it, left + cellWidth - 30, top + cellHeight - 15, paintFlickText) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                pressedCol = (startX / cellWidth).toInt().coerceIn(0, cols - 1)
                pressedRow = (startY / cellHeight).toInt().coerceIn(0, rows - 1)
                invalidate()
                if (pressedRow == 0 && pressedCol == 4) {
                    backspaceListener?.invoke()
                    repeatHandler.postDelayed(repeatRunnable, 400)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                if (!(pressedRow == 0 && pressedCol == 4) && pressedRow != -1) {
                    val dist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    val key = layoutMap[Pair(pressedRow, pressedCol)]
                    val dir = if (dist < 40f) FlickDirection.TAP else calculateDir(startX, startY, event.x, event.y, key?.is8Way ?: false)
                    keyActionListener?.invoke(pressedRow, pressedCol, dir)
                }
                pressedRow = -1; pressedCol = -1
                invalidate()
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
            when (angle) {
                in 315.0..360.0, in 0.0..45.0 -> FlickDirection.RIGHT
                in 45.0..135.0 -> FlickDirection.DOWN
                in 135.0..225.0 -> FlickDirection.LEFT
                else -> FlickDirection.UP
            }
        }
    }
}
