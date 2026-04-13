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
import kotlin.math.cos
import kotlin.math.sin

class TaukiTaukiView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // ─── Grid ─────────────────────────────────────────────────────────────────

    private val BASQUE_ROWS = 4
    private val BASQUE_COLS = 5

    // Row 3: mode(0) ,(1) ␣(2+3 double) .(4) ←(5) →(6) ⏎(7)  → 8 unit slots
    private val qwertyRowCols = intArrayOf(10, 9, 9, 8)

    private var cellWidth  = 0f
    private var cellHeight = 0f

    private val fastKeyLookup = Array<FlickKey?>(BASQUE_ROWS * BASQUE_COLS) { null }

    var layoutMap: Map<Pair<Int, Int>, FlickKey> = emptyMap()
        set(value) {
            field = value
            for (r in 0 until BASQUE_ROWS)
                for (c in 0 until BASQUE_COLS)
                    fastKeyLookup[r * BASQUE_COLS + c] = value[Pair(r, c)]
            invalidate()
        }

    var qwertyRows: List<List<Pair<String, FlickKey?>>> = emptyList()

    // The full QWERTY FlickKey grid — set by the service, used for drawing flick hints.
    // Rows 0-2 only; row 3 punct keys are stored separately.
    var qwertyFlickLayout: List<List<FlickKey?>> = emptyList()

    // FlickKey definitions for punctuation slots in QWERTY row 3 (col→key).
    var qwertyPunctKeys: Map<Int, FlickKey> = emptyMap()

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
    private val paintDirHighlight   = Paint().apply { style = Paint.Style.FILL; color = 0xFF556677.toInt() }
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

    // Live drag direction: null = no drag / tap zone. 0..7 = direction index (same as FlickKey angle order)
    // Directions: 0=right, 1=dr, 2=down, 3=dl, 4=left, 5=ul, 6=up, 7=ur
    private var dragDirIndex: Int? = null
    private val DRAG_THRESHOLD = 18f   // px before we leave the tap zone

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
        // Always compute both, since qwertyActive can change without onSizeChanged firing again.
        cellWidth  = w.toFloat() / BASQUE_COLS
        cellHeight = h.toFloat() / BASQUE_ROWS   // same row count as qwertyRowCols.size (4)
        paintCenterText.textSize = cellHeight * 0.36f
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
            if (row == 3 && col == 3) col = 2
            Pair(row, col)
        }
    }

    // Returns the cell rect (left, top, right, bottom) for a given (row, col) in whichever mode.
    private fun cellRect(r: Int, c: Int): RectF {
        return if (!isQwerty) {
            RectF(c * cellWidth, r * cellHeight, (c + 1) * cellWidth, (r + 1) * cellHeight)
        } else {
            val unitWidth = width.toFloat() / qwertyRowCols[r]
            val colSpan = if (r == 3 && c == 2) 2 else 1
            RectF(c * unitWidth, r * cellHeight, (c + colSpan) * unitWidth, (r + 1) * cellHeight)
        }
    }

    // ─── Direction sector highlight ──────────────────────────────────────────────

    // Direction indices: 0=right(0°) 1=dr(45°) 2=down(90°) 3=dl(135°)
    //                    4=left(180°) 5=ul(225°) 6=up(270°) 7=ur(315°)
    private fun angleToDir(angle: Double): Int {
        val a = ((angle % 360) + 360) % 360
        return ((a + 22.5) / 45).toInt() % 8
    }

    // Returns the set of configured direction indices for a FlickKey (excludes tap).
    private fun configuredDirs(key: FlickKey?): Set<Int> {
        if (key == null) return emptySet()
        val dirs = mutableSetOf<Int>()
        if (key.right != null) dirs.add(0)
        if (key.dr    != null) dirs.add(1)
        if (key.down  != null) dirs.add(2)
        if (key.dl    != null) dirs.add(3)
        if (key.left  != null) dirs.add(4)
        if (key.ul    != null) dirs.add(5)
        if (key.up    != null) dirs.add(6)
        if (key.ur    != null) dirs.add(7)
        return dirs
    }

    // Draw the sector wedge for the given direction: apex at cell centre, sides
    // extend to the cell boundary clipped by the ±22.5° half-sector lines.
    private fun drawSectorHighlight(canvas: Canvas, rect: RectF, dirIndex: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w  = rect.width()
        val h  = rect.height()

        // The sector spans ±22.5° around the direction angle.
        val midDeg  = dirIndex * 45.0
        val halfDeg = 22.5
        val leftDeg  = midDeg - halfDeg
        val rightDeg = midDeg + halfDeg

        // Compute intersection of a ray from centre at given angle with the cell boundary.
        fun rayHit(deg: Double): PointF {
            val rad = Math.toRadians(deg)
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            // Find t for each edge, take the smallest positive t that actually hits an edge.
            val ts = mutableListOf<Float>()
            if (dx > 0)  ts.add((rect.right  - cx) / dx)
            if (dx < 0)  ts.add((rect.left   - cx) / dx)
            if (dy > 0)  ts.add((rect.bottom - cy) / dy)
            if (dy < 0)  ts.add((rect.top    - cy) / dy)
            val t = ts.filter { it > 0 }.minOrNull() ?: 1f
            return PointF(cx + dx * t, cy + dy * t)
        }

        val pLeft  = rayHit(leftDeg)
        val pRight = rayHit(rightDeg)

        // Build a polygon: centre → left boundary point → (optional corner) → right boundary point → centre
        val path = Path()
        path.moveTo(cx, cy)
        path.lineTo(pLeft.x, pLeft.y)

        // If the sector spans a corner of the rectangle, include that corner so the sector
        // fills flush to the edge rather than cutting across it.
        // A corner is included if its angle from centre falls within [leftDeg, rightDeg].
        val corners = listOf(
            PointF(rect.right, rect.top)    to Math.toDegrees(atan2((rect.top    - cy).toDouble(), (rect.right - cx).toDouble())),
            PointF(rect.right, rect.bottom) to Math.toDegrees(atan2((rect.bottom - cy).toDouble(), (rect.right - cx).toDouble())),
            PointF(rect.left,  rect.bottom) to Math.toDegrees(atan2((rect.bottom - cy).toDouble(), (rect.left  - cx).toDouble())),
            PointF(rect.left,  rect.top)    to Math.toDegrees(atan2((rect.top    - cy).toDouble(), (rect.left  - cx).toDouble())),
        )
        for ((corner, cornerDeg) in corners) {
            // Normalise corner angle to same base as our sector
            val norm = ((cornerDeg - leftDeg) % 360 + 360) % 360
            val span = ((rightDeg - leftDeg) % 360 + 360) % 360
            if (norm in 0.0..span) path.lineTo(corner.x, corner.y)
        }

        path.lineTo(pRight.x, pRight.y)
        path.close()
        canvas.drawPath(path, paintDirHighlight)
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
                val rect = RectF(left, top, left + cellWidth, top + cellHeight)
                drawBasqueCellBackground(canvas, r, c, left, top)
                if (r == pressedRow && c == pressedCol) {
                    val key = fastKeyLookup[r * BASQUE_COLS + c]
                    val dirs = configuredDirs(key)
                    val dir = dragDirIndex
                    when {
                        dir != null && dir in dirs -> drawSectorHighlight(canvas, rect, dir)
                        else -> canvas.drawRect(rect, paintHighlight)
                    }
                }
                canvas.drawRect(rect, paintGrid)
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
                if (r == 3 && c == 3) continue  // consumed by double-width spacebar
                val left = c * unitWidth
                val colWidth = if (r == 3 && c == 2) unitWidth * 2 else unitWidth
                val rect = RectF(left, top, left + colWidth, top + cellHeight)
                drawQwertyCellBackground(canvas, r, c, left, top, colWidth)
                if (r == pressedRow && c == pressedCol) {
                    // QWERTY keys are tap-only (no flick directions), so always full highlight
                    canvas.drawRect(rect, paintHighlight)
                }
                canvas.drawRect(rect, paintGrid)
                drawQwertyCellContent(canvas, r, c, left, top, colWidth)
            }
        }
    }

    private fun drawQwertyCellBackground(canvas: Canvas, r: Int, c: Int, left: Float, top: Float, colWidth: Float) {
        val paint = when {
            r == 3 && c == 7 -> paintEnterKey
            r == 2 && c == 0 -> when (currentMode) {
                KeyboardMode.UPPER -> paintShiftUppercase
                KeyboardMode.TITLE -> paintShiftActive
                else               -> paintSpecialKey
            }
            r == 2 && c == 8 -> paintSpecialKey
            r == 3 && c == 0 -> paintSpecialKey
            r == 3 && c == 2 -> paintSpecialKey
            r == 3 && c == 5 -> paintSpecialKey
            r == 3 && c == 6 -> paintSpecialKey
            else -> return
        }
        canvas.drawRect(left, top, left + colWidth, top + cellHeight, paint)
    }

    private fun drawQwertyCellContent(canvas: Canvas, r: Int, c: Int, left: Float, top: Float, colWidth: Float) {
        val cx = left + colWidth / 2
        val cy = top + cellHeight / 2 + paintCenterText.textSize / 3

        // Row-3 punct keys (comma col=1, period col=4) use FlickKey for flick hints
        if (r == 3) {
            val punctKey = qwertyPunctKeys[c]
            if (punctKey != null) {
                drawFlickKeyLabels(canvas, punctKey, left, top, colWidth, cellHeight)
                return
            }
        }

        // Other special keys (shift, backspace, mode bar, space, arrows, enter)
        val specialLabel = qwertySpecialLabelFor(r, c)
        if (specialLabel != null) {
            canvas.drawText(specialLabel, cx, cy, paintCenterText)
            return
        }

        // Letter/symbol keys: draw using FlickKey if available, else plain label
        val flickKey = qwertyFlickLayout.getOrNull(r)?.getOrNull(c)
        if (flickKey != null) {
            drawFlickKeyLabels(canvas, flickKey, left, top, colWidth, cellHeight)
        } else {
            val label = qwertyKeyLabelFor(r, c) ?: return
            val display = when {
                isUppercase -> label.uppercase()
                isShifted   -> label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                else        -> label
            }
            canvas.drawText(display, cx, cy, paintCenterText)
        }
    }

    private fun qwertySpecialLabelFor(r: Int, c: Int): String? = when {
        r == 2 && c == 0 -> "⇧"
        r == 2 && c == 8 -> context.getString(R.string.label_backspace)
        r == 3 && c == 0 -> modeLabel
        r == 3 && c == 1 -> ","
        r == 3 && c == 2 -> context.getString(R.string.label_space_key)
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
            listOf("","z","x","c","v","b","n","m","")
        )
        return rows.getOrNull(r)?.getOrNull(c)?.takeIf { it.isNotEmpty() }
    }

    // ─── Flick key label drawing (Basque/Num and QWERTY) ────────────────────────

    private fun drawFlickKeyLabels(canvas: Canvas, key: FlickKey, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w / 2
        val cy = top  + h / 2

        fun fmt(s: String?) = when {
            s == null   -> null
            isUppercase -> s.uppercase()
            isShifted   -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            else        -> s
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
                dragDirIndex = null
                invalidate()
                val isBackspace = (!isQwerty && r == 0 && c == 4) || (isQwerty && r == 2 && c == 8)
                if (isBackspace) {
                    backspaceListener?.invoke()
                    repeatHandler.postDelayed(repeatRunnable, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedRow != -1) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val dist = hypot(dx.toDouble(), dy.toDouble())
                    dragDirIndex = if (dist < DRAG_THRESHOLD) {
                        null  // still in tap zone
                    } else {
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                        if (angle < 0) angle += 360
                        angleToDir(angle)
                    }
                    invalidate()
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
                dragDirIndex = null
                invalidate()
            }
        }
        return true
    }
}
