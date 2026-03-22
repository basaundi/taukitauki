package eus.basaundi.taukitauki

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

class TaukiTaukiView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // ─── Grid ─────────────────────────────────────────────────────────────────

    private val rows = 4
    private val cols = 5
    private var cellWidth  = 0f
    private var cellHeight = 0f

    // Fast flat-array lookup so onDraw avoids Map hashing every frame
    private val fastKeyLookup = Array<FlickKey?>(rows * cols) { null }

    var layoutMap: Map<Pair<Int, Int>, FlickKey> = emptyMap()
        set(value) {
            field = value
            for (r in 0 until rows)
                for (c in 0 until cols)
                    fastKeyLookup[r * cols + c] = value[Pair(r, c)]
            invalidate()
        }

    // ─── Display state ────────────────────────────────────────────────────────

    var currentMode = KeyboardMode.LOWER
    var modeLabel   = context.getString(R.string.mode_lower)
    var swapLabel   = context.getString(R.string.label_swap)

    // Convenience shorthands used only in drawKeyLabels
    private val isUppercase get() = currentMode == KeyboardMode.UPPER
    private val isShifted   get() = currentMode == KeyboardMode.TITLE

    // ─── Paints ───────────────────────────────────────────────────────────────

    private val paintGrid           = Paint().apply { strokeWidth = 2f; style = Paint.Style.STROKE; color = 0xFF333333.toInt() }
    private val paintHighlight      = Paint().apply { style = Paint.Style.FILL; color = 0xFF444444.toInt() }
    private val paintSpecialKey     = Paint().apply { style = Paint.Style.FILL; color = 0xFF222222.toInt() }
    private val paintShiftActive    = Paint().apply { style = Paint.Style.FILL; color = 0xFF555555.toInt() }
    private val paintShiftUppercase = Paint().apply { style = Paint.Style.FILL; color = 0xFF1976D2.toInt() }
    private val paintEnterKey       = Paint().apply { style = Paint.Style.FILL; color = 0xFF1565C0.toInt() }
    private val paintCenterText     = Paint().apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Color.WHITE }
    private val paintFlickText      = Paint().apply { textAlign = Paint.Align.CENTER; textSize = 26f; color = Color.LTGRAY }

    // ─── Listeners ────────────────────────────────────────────────────────────

    var keyActionListener:  ((row: Int, col: Int, gesture: Gesture) -> Unit)? = null
    var longPressListener:  ((row: Int, col: Int) -> Unit)? = null
    var backspaceListener:  (() -> Unit)? = null

    // ─── Touch tracking ───────────────────────────────────────────────────────

    private var pressedRow = -1
    private var pressedCol = -1
    private var startX = 0f
    private var startY = 0f

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

    // ─── Size ─────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellWidth  = w.toFloat() / cols
        cellHeight = h.toFloat() / rows
        paintCenterText.textSize = cellHeight * 0.35f
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * cellWidth
                val top  = r * cellHeight
                drawCellBackground(canvas, r, c, left, top)
                if (r == pressedRow && c == pressedCol)
                    canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintHighlight)
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintGrid)
                drawCellContent(canvas, r, c, left, top)
            }
        }
    }

    private fun drawCellBackground(canvas: Canvas, r: Int, c: Int, left: Float, top: Float) {
        val paint = when {
            r == 3 && c == 4 -> paintEnterKey
            r == 2 && c == 0 -> when (currentMode) {
                KeyboardMode.UPPER  -> paintShiftUppercase
                KeyboardMode.TITLE  -> paintShiftActive
                else                -> paintSpecialKey
            }
            isSpecialCell(r, c) -> paintSpecialKey
            else -> return
        }
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
    }

    private fun isSpecialCell(r: Int, c: Int) =
        (r == 0 && c == 4) || (r == 1 && c == 0) || (r == 1 && c == 4) ||
        (r == 2 && c == 4) || (r == 3 && c == 0) || (r == 3 && c == 1)

    private fun drawCellContent(canvas: Canvas, r: Int, c: Int, left: Float, top: Float) {
        val specialLabel = specialLabelFor(r, c)
        if (specialLabel != null) {
            canvas.drawText(specialLabel, left + cellWidth / 2, top + cellHeight / 2 + paintCenterText.textSize / 3, paintCenterText)
        } else {
            fastKeyLookup[r * cols + c]?.let { drawKeyLabels(canvas, it, left, top) }
        }
    }

    private fun specialLabelFor(r: Int, c: Int): String? = when {
        r == 0 && c == 4 -> context.getString(R.string.label_backspace)
        r == 1 && c == 0 -> context.getString(R.string.label_left)
        r == 1 && c == 4 -> context.getString(R.string.label_right)
        r == 2 && c == 4 -> context.getString(R.string.label_space_key)
        r == 3 && c == 0 -> modeLabel
        r == 3 && c == 1 -> swapLabel
        r == 3 && c == 4 -> context.getString(R.string.label_enter)
        else              -> null
    }

    private fun drawKeyLabels(canvas: Canvas, key: FlickKey, left: Float, top: Float) {
        val cx = left + cellWidth / 2
        val cy = top  + cellHeight / 2

        fun fmt(s: String?) = when {
            s == null    -> null
            isUppercase  -> s.uppercase()
            isShifted    -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            else         -> s
        }

        fmt(key.tap)?.let   { canvas.drawText(it, cx,                    cy + 15,                       paintCenterText) }
        fmt(key.up)?.let    { canvas.drawText(it, cx,                    top + 35,                      paintFlickText)  }
        fmt(key.down)?.let  { canvas.drawText(it, cx,                    top + cellHeight - 15,          paintFlickText)  }
        fmt(key.left)?.let  { canvas.drawText(it, left + 25,             cy + 10,                       paintFlickText)  }
        fmt(key.right)?.let { canvas.drawText(it, left + cellWidth - 25, cy + 10,                       paintFlickText)  }
        fmt(key.ul)?.let    { canvas.drawText(it, left + 30,             top + 35,                      paintFlickText)  }
        fmt(key.ur)?.let    { canvas.drawText(it, left + cellWidth - 30, top + 35,                      paintFlickText)  }
        fmt(key.dl)?.let    { canvas.drawText(it, left + 30,             top + cellHeight - 15,          paintFlickText)  }
        fmt(key.dr)?.let    { canvas.drawText(it, left + cellWidth - 30, top + cellHeight - 15,          paintFlickText)  }
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

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
                    val dist  = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    var angle = Math.toDegrees(atan2((event.y - startY).toDouble(), (event.x - startX).toDouble()))
                    if (angle < 0) angle += 360
                    keyActionListener?.invoke(pressedRow, pressedCol, Gesture(isTap = dist < 40f, angle = angle))
                }
                pressedRow = -1; pressedCol = -1
                invalidate()
            }
        }
        return true
    }
}
