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

    // ─── Grid (fixed for Basque/Num; dynamic for QWERTY) ─────────────────────

    private val BASQUE_ROWS = 4
    private val BASQUE_COLS = 5

    // QWERTY row definitions: list of (label, col-index-within-row) — we map to
    // a virtual grid where each row has its own column count.
    // Row 0: q w e r t y u i o p          (10 keys, col 0-9)
    // Row 1: a s d f g h j k l            (9 keys,  col 0-8)
    // Row 2: ⇧ z x c v b n m ⌫           (9 keys,  col 0-8)
    // Row 3: mode , ␣ . ← → ⏎            (7 keys,  col 0-6)
    // QWERTY row definitions — number of equal-width unit slots per row.
    // Row 0: q w e r t y u i o p          (10 keys, col 0-9)
    // Row 1: a s d f g h j k l            (9 keys,  col 0-8)
    // Row 2: ⇧ z x c v b n m ⌫           (9 keys,  col 0-8)
    // Row 3: mode(0) ,(1) ␣(2+3) .(4) ←(5) →(6) ⏎(7)
    //        8 unit slots; spacebar spans slots 2-3 (double width).
    //        Hit on slot 3 is remapped to col 2 (space) before firing.
    private val qwertyRowCols = intArrayOf(10, 9, 9, 8)

    // For QWERTY the View uses a separate rendering path; row/col here are
    // indices into qwertyRowCols.
    // For Basque/Num the existing fixed-grid path is used unchanged.

    private var cellWidth  = 0f
    private var cellHeight = 0f

    // Fast flat-array lookup for Basque/Num modes
    private val fastKeyLookup = Array<FlickKey?>(BASQUE_ROWS * BASQUE_COLS) { null }

    var layoutMap: Map<Pair<Int, Int>, FlickKey> = emptyMap()
        set(value) {
            field = value
            for (r in 0 until BASQUE_ROWS)
                for (c in 0 until BASQUE_COLS)
                    fastKeyLookup[r * BASQUE_COLS + c] = value[Pair(r, c)]
            invalidate()
        }

    // QWERTY layout: row -> list of (label, FlickKey?)
    // Special keys have null FlickKey and are handled by label.
    var qwertyRows: List<List<Pair<String, FlickKey?>>> = emptyList()

    // ─── Display state ────────────────────────────────────────────────────────

    var currentMode  = KeyboardMode.LOWER
    var qwertyActive = false
    var modeLabel    = context.getString(R.string.mode_lower)
    var swapLabel    = context.getString(R.string.label_swap)

    private val isUppercase get() = currentMode == KeyboardMode.UPPER
    private val isShifted   get() = currentMode == KeyboardMode.TITLE
    private val isQwerty    get() = qwertyActive

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
            val (r, c) = hitTest(e.x, e.y)
            longPressListener?.invoke(r, c)
        }
    })

    // ─── Size ─────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isQwerty) {
            cellWidth  = w.toFloat() / BASQUE_COLS
            cellHeight = h.toFloat() / BASQUE_ROWS
            paintCenterText.textSize = cellHeight * 0.35f
        } else {
            cellHeight = h.toFloat() / qwertyRowCols.size
            paintCenterText.textSize = cellHeight * 0.38f
        }
    }

    // ─── Hit testing ──────────────────────────────────────────────────────────

    private fun hitTest(x: Float, y: Float): Pair<Int, Int> {
        return if (!isQwerty) {
            val c = (x / cellWidth).toInt().coerceIn(0, BASQUE_COLS - 1)
            val r = (y / cellHeight).toInt().coerceIn(0, BASQUE_ROWS - 1)
            Pair(r, c)
        } else {
            val row = (y / cellHeight).toInt().coerceIn(0, qwertyRowCols.size - 1)
            val cols = qwertyRowCols[row]
            val colWidth = width.toFloat() / cols
            var col = (x / colWidth).toInt().coerceIn(0, cols - 1)
            // Row 3: slot 3 is the right half of the double-width spacebar → remap to col 2
            if (row == 3 && col == 3) col = 2
            Pair(row, col)
        }
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (isQwerty) drawQwerty(canvas) else drawBasque(canvas)
    }

    private fun drawBasque(canvas: Canvas) {
        for (r in 0 until BASQUE_ROWS) {
            for (c in 0 until BASQUE_COLS) {
                val left = c * cellWidth
                val top  = r * cellHeight
                drawBasqueCellBackground(canvas, r, c, left, top)
                if (r == pressedRow && c == pressedCol)
                    canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintHighlight)
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paintGrid)
                drawBasqueCellContent(canvas, r, c, left, top)
            }
        }
    }

    private fun drawBasqueCellBackground(canvas: Canvas, r: Int, c: Int, left: Float, top: Float) {
        val paint = when {
            r == 3 && c == 4 -> paintEnterKey
            r == 2 && c == 0 -> when (currentMode) {
                KeyboardMode.UPPER -> paintShiftUppercase
                KeyboardMode.TITLE -> paintShiftActive
                else               -> paintSpecialKey
            }
            isBasqueSpecialCell(r, c) -> paintSpecialKey
            else -> return
        }
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
    }

    private fun isBasqueSpecialCell(r: Int, c: Int) =
        (r == 0 && c == 4) || (r == 1 && c == 0) || (r == 1 && c == 4) ||
        (r == 2 && c == 4) || (r == 3 && c == 0) || (r == 3 && c == 1)

    private fun drawBasqueCellContent(canvas: Canvas, r: Int, c: Int, left: Float, top: Float) {
        val label = basqueSpecialLabelFor(r, c)
        if (label != null) {
            canvas.drawText(label, left + cellWidth / 2, top + cellHeight / 2 + paintCenterText.textSize / 3, paintCenterText)
        } else {
            fastKeyLookup[r * BASQUE_COLS + c]?.let { drawFlickKeyLabels(canvas, it, left, top, cellWidth, cellHeight) }
        }
    }

    private fun basqueSpecialLabelFor(r: Int, c: Int): String? = when {
        r == 0 && c == 4 -> context.getString(R.string.label_backspace)
        r == 1 && c == 0 -> context.getString(R.string.label_left)
        r == 1 && c == 4 -> context.getString(R.string.label_right)
        r == 2 && c == 4 -> context.getString(R.string.label_space_key)
        r == 3 && c == 0 -> modeLabel
        r == 3 && c == 1 -> swapLabel
        r == 3 && c == 4 -> context.getString(R.string.label_enter)
        else              -> null
    }

    private fun drawQwerty(canvas: Canvas) {
        val rows = qwertyRowCols.size
        for (r in 0 until rows) {
            val numCols = qwertyRowCols[r]
            val unitWidth = width.toFloat() / numCols
            val top = r * cellHeight
            for (c in 0 until numCols) {
                // Row 3: slot 3 is consumed by the double-width spacebar drawn at slot 2 — skip it
                if (r == 3 && c == 3) continue
                val left = c * unitWidth
                // Spacebar (row 3, col 2) spans two unit slots
                val colWidth = if (r == 3 && c == 2) unitWidth * 2 else unitWidth
                drawQwertyCellBackground(canvas, r, c, left, top, colWidth)
                if (r == pressedRow && c == pressedCol)
                    canvas.drawRect(left, top, left + colWidth, top + cellHeight, paintHighlight)
                canvas.drawRect(left, top, left + colWidth, top + cellHeight, paintGrid)
                drawQwertyCellContent(canvas, r, c, left, top, colWidth)
            }
        }
    }

    private fun drawQwertyCellBackground(canvas: Canvas, r: Int, c: Int, left: Float, top: Float, colWidth: Float) {
        val paint = when {
            r == 3 && c == 7 -> paintEnterKey               // ⏎  (slot 7)
            r == 2 && c == 0 -> when (currentMode) {        // ⇧
                KeyboardMode.UPPER -> paintShiftUppercase
                KeyboardMode.TITLE -> paintShiftActive
                else               -> paintSpecialKey
            }
            r == 2 && c == 8 -> paintSpecialKey             // ⌫
            r == 3 && c == 0 -> paintSpecialKey             // mode
            r == 3 && c == 2 -> paintSpecialKey             // ␣  (double-width, slots 2-3)
            r == 3 && c == 5 -> paintSpecialKey             // ←  (slot 5)
            r == 3 && c == 6 -> paintSpecialKey             // →  (slot 6)
            else -> return
        }
        canvas.drawRect(left, top, left + colWidth, top + cellHeight, paint)
    }

    private fun drawQwertyCellContent(canvas: Canvas, r: Int, c: Int, left: Float, top: Float, colWidth: Float) {
        val cx = left + colWidth / 2
        val cy = top + cellHeight / 2 + paintCenterText.textSize / 3
        val label = qwertySpecialLabelFor(r, c) ?: qwertyKeyLabelFor(r, c)
        if (label != null) {
            val display = if (isUppercase) label.uppercase()
                          else if (isShifted) label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                          else label
            canvas.drawText(display, cx, cy, paintCenterText)
        }
    }

    private fun qwertySpecialLabelFor(r: Int, c: Int): String? = when {
        r == 2 && c == 0 -> "⇧"
        r == 2 && c == 8 -> context.getString(R.string.label_backspace)
        r == 3 && c == 0 -> modeLabel
        r == 3 && c == 1 -> ","
        r == 3 && c == 2 -> context.getString(R.string.label_space_key)  // double-width
        r == 3 && c == 4 -> "."
        r == 3 && c == 5 -> context.getString(R.string.label_left)
        r == 3 && c == 6 -> context.getString(R.string.label_right)
        r == 3 && c == 7 -> context.getString(R.string.label_enter)
        else              -> null
    }

    private fun qwertyKeyLabelFor(r: Int, c: Int): String? {
        val rows = listOf(
            listOf("q","w","e","r","t","y","u","i","o","p"),
            listOf("a","s","d","f","g","h","j","k","l"),
            listOf("","z","x","c","v","b","n","m","")  // 0=shift, 8=backspace handled above
        )
        return rows.getOrNull(r)?.getOrNull(c)?.takeIf { it.isNotEmpty() }
    }

    // ─── Flick key label drawing (shared by Basque/Num) ──────────────────────

    private fun drawFlickKeyLabels(canvas: Canvas, key: FlickKey, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w / 2
        val cy = top  + h / 2

        fun fmt(s: String?) = when {
            s == null    -> null
            isUppercase  -> s.uppercase()
            isShifted    -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            else         -> s
        }

        fmt(key.tap)?.let   { canvas.drawText(it, cx,          cy + 15,          paintCenterText) }
        fmt(key.up)?.let    { canvas.drawText(it, cx,          top + 35,          paintFlickText)  }
        fmt(key.down)?.let  { canvas.drawText(it, cx,          top + h - 15,      paintFlickText)  }
        fmt(key.left)?.let  { canvas.drawText(it, left + 25,   cy + 10,           paintFlickText)  }
        fmt(key.right)?.let { canvas.drawText(it, left + w-25, cy + 10,           paintFlickText)  }
        fmt(key.ul)?.let    { canvas.drawText(it, left + 30,   top + 35,          paintFlickText)  }
        fmt(key.ur)?.let    { canvas.drawText(it, left + w-30, top + 35,          paintFlickText)  }
        fmt(key.dl)?.let    { canvas.drawText(it, left + 30,   top + h - 15,      paintFlickText)  }
        fmt(key.dr)?.let    { canvas.drawText(it, left + w-30, top + h - 15,      paintFlickText)  }
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                val (r, c) = hitTest(startX, startY)
                pressedRow = r; pressedCol = c
                invalidate()
                // Backspace: basque=(0,4), qwerty=(2,8)
                val isBackspace = (!isQwerty && r == 0 && c == 4) || (isQwerty && r == 2 && c == 8)
                if (isBackspace) {
                    backspaceListener?.invoke()
                    repeatHandler.postDelayed(repeatRunnable, 400)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                val (pr, pc) = Pair(pressedRow, pressedCol)
                val isBackspace = (!isQwerty && pr == 0 && pc == 4) || (isQwerty && pr == 2 && pc == 8)
                if (!isBackspace && pr != -1) {
                    val dist  = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    var angle = Math.toDegrees(atan2((event.y - startY).toDouble(), (event.x - startX).toDouble()))
                    if (angle < 0) angle += 360
                    keyActionListener?.invoke(pr, pc, Gesture(isTap = dist < 40f, angle = angle))
                }
                pressedRow = -1; pressedCol = -1
                invalidate()
            }
        }
        return true
    }
}
