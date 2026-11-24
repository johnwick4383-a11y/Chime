package com.aireply.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

// What the keyboard view reports back to the service that owns it.
interface KeyboardListener {
    fun onText(s: String)
    fun onDelete()
    fun onEnter()
    fun onCursor(steps: Int)
}

// The typing surface. Everything is drawn on one Canvas, so it is fast and looks the same on every phone.
// Behaviour copied from Samsung Keyboard: shift / double-tap caps lock, held backspace repeats,
// long-press letters for accents and digits, long-press . or , for symbols (slide to pick),
// hold the space bar and slide to move the cursor, symbols pages 1/2 and 2/2.
class KeyboardView(ctx: Context, private val listener: KeyboardListener) : View(ctx) {

    var dark = false
        set(v) { field = v; invalidate() }
    var numberRow = false
        set(v) { if (v != field) { field = v; rebuild(); requestLayout() } }
    var enterLabel = ""
        set(v) { field = v; invalidate() }

    private val accent: Int = 0xFF3E6BFF.toInt()
    private val white: Int = 0xFFFFFFFF.toInt()

    private var page = 0          // 0 letters, 1 symbols 1/2, 2 symbols 2/2
    private var shift = 0         // 0 off, 1 one capital, 2 caps lock
    private var lastShiftTap = 0L
    private var rows: List<List<Key>> = Layouts.letters(false)
    private val keys = ArrayList<Pair<Key, RectF>>()
    private val handler = Handler(Looper.getMainLooper())

    private var pressed: Pair<Key, RectF>? = null
    private var curX = 0f
    private var lastCursorX = 0f
    private var cursorMode = false
    private var popup: List<String>? = null
    private var popupIndex = 0
    private var popupLeft = 0f
    private var popupTop = 0f
    private var popupCell = 0f
    private var pid = -1
    private var lastX = 0f
    private var lastY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val repeatDel = object : Runnable {
        override fun run() {
            if (pressed?.first?.type == KType.DEL) {
                listener.onDelete()
                handler.postDelayed(this, 55)
            }
        }
    }
    private val longPress = Runnable { showPopup() }
    private val startCursor = Runnable {
        cursorMode = true
        lastCursorX = curX
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun fg(): Int = if (dark) 0xFFFFFFFF.toInt() else 0xFF1B1C1F.toInt()

    // ---- public controls used by the service -------------------------------------------------

    fun reset() {
        cancel()
        page = 0
        shift = 0
        rebuild()
    }

    fun setAutoShift(on: Boolean) {
        if (page == 0 && shift != 2) {
            val n = if (on) 1 else 0
            if (n != shift) { shift = n; invalidate() }
        }
    }

    // ---- layout ------------------------------------------------------------------------------

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (dp(52f) * (if (numberRow) 5 else 4)).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutKeys()
    }

    private fun rebuild() {
        rows = if (page == 0) Layouts.letters(numberRow) else Layouts.symbols(page)
        layoutKeys()
        invalidate()
    }

    private fun layoutKeys() {
        keys.clear()
        if (width == 0 || rows.isEmpty()) return
        val rowH = height.toFloat() / rows.size
        val gap = dp(3f)
        rows.forEachIndexed { r, row ->
            var total = 0f
            for (k in row) total += k.weight
            var x = 0f
            val top = r * rowH
            for (k in row) {
                val w = width * k.weight / total
                if (k.type != KType.GAP) {
                    keys.add(Pair(k, RectF(x + gap, top + gap * 1.3f, x + w - gap, top + rowH - gap * 1.3f)))
                }
                x += w
            }
        }
    }

    private fun label(k: Key): String =
        if (page == 0 && shift != 0 && k.type == KType.CHAR) k.label.uppercase() else k.label

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        c.drawColor(if (dark) 0xFF1B1C1F.toInt() else 0xFFE9EAEE.toInt())
        val rad = dp(7f)
        for (p in keys) drawKey(c, p.first, p.second, p === pressed, rad)
        val pr = pressed
        if (pr != null && pr.first.type == KType.CHAR && popup == null && !cursorMode) drawPreview(c, pr)
        val pp = popup
        if (pp != null) drawPopup(c, pp)
    }

    private fun drawKey(c: Canvas, k: Key, r: RectF, down: Boolean, rad: Float) {
        val special = k.type != KType.CHAR && k.type != KType.SPACE
        fill.color = when {
            k.type == KType.ENTER -> if (down) 0xFF2F55D4.toInt() else accent
            down -> if (dark) 0xFF55565C.toInt() else 0xFFB9BDC8.toInt()
            special -> if (dark) 0xFF2A2B2F.toInt() else 0xFFCFD2DA.toInt()
            else -> if (dark) 0xFF3B3C41.toInt() else white
        }
        c.drawRoundRect(r, rad, rad, fill)
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.height() * 0.17f
        stroke.strokeWidth = dp(2f)
        when (k.type) {
            KType.CHAR -> {
                tp.color = fg()
                tp.textSize = dp(if (page == 0) 21f else 19f)
                c.drawText(label(k), cx, cy + tp.textSize * 0.35f, tp)
                if (page == 0 && !numberRow && k.alts.isNotEmpty() && k.alts[0].isDigit()) {
                    tp.color = if (dark) 0xFF9A9DA6.toInt() else 0xFF8A8E99.toInt()
                    tp.textSize = dp(10f)
                    c.drawText(k.alts.substring(0, 1), r.right - dp(8f), r.top + dp(13f), tp)
                }
            }
            KType.SPACE -> {
                tp.color = 0xFF8A8E99.toInt()
                tp.textSize = dp(12f)
                c.drawText("AI Keyboard", cx, cy + dp(4f), tp)
            }
            KType.SHIFT -> {
                val p = Path()
                p.moveTo(cx, cy - s * 1.2f)
                p.lineTo(cx + s * 1.3f, cy)
                p.lineTo(cx + s * 0.55f, cy)
                p.lineTo(cx + s * 0.55f, cy + s)
                p.lineTo(cx - s * 0.55f, cy + s)
                p.lineTo(cx - s * 0.55f, cy)
                p.lineTo(cx - s * 1.3f, cy)
                p.close()
                stroke.color = fg()
                fill.color = fg()
                if (shift != 0) c.drawPath(p, fill) else c.drawPath(p, stroke)
                if (shift == 2) c.drawLine(cx - s * 0.55f, cy + s * 1.6f, cx + s * 0.55f, cy + s * 1.6f, stroke)
            }
            KType.DEL -> {
                val p = Path()
                p.moveTo(cx - s * 1.5f, cy)
                p.lineTo(cx - s * 0.6f, cy - s)
                p.lineTo(cx + s * 1.5f, cy - s)
                p.lineTo(cx + s * 1.5f, cy + s)
                p.lineTo(cx - s * 0.6f, cy + s)
                p.close()
                stroke.color = fg()
                c.drawPath(p, stroke)
                c.drawLine(cx - s * 0.1f, cy - s * 0.45f, cx + s * 0.8f, cy + s * 0.45f, stroke)
                c.drawLine(cx + s * 0.8f, cy - s * 0.45f, cx - s * 0.1f, cy + s * 0.45f, stroke)
            }
            KType.ENTER -> {
                if (enterLabel.isEmpty()) {
                    stroke.color = white
                    c.drawLine(cx + s * 1.2f, cy - s, cx + s * 1.2f, cy, stroke)
                    c.drawLine(cx + s * 1.2f, cy, cx - s * 1.2f, cy, stroke)
                    c.drawLine(cx - s * 1.2f, cy, cx - s * 0.4f, cy - s * 0.7f, stroke)
                    c.drawLine(cx - s * 1.2f, cy, cx - s * 0.4f, cy + s * 0.7f, stroke)
                } else {
                    tp.color = white
                    tp.textSize = dp(14f)
                    c.drawText(enterLabel, cx, cy + tp.textSize * 0.35f, tp)
                }
            }
            else -> {
                tp.color = fg()
                tp.textSize = dp(15f)
                c.drawText(k.label, cx, cy + tp.textSize * 0.35f, tp)
            }
        }
    }

    private fun drawPreview(c: Canvas, p: Pair<Key, RectF>) {
        val r = p.second
        val w = r.width() * 1.4f
        val h = r.height() * 1.15f
        val left = (r.centerX() - w / 2).coerceIn(0f, maxOf(0f, width - w))
        val top = maxOf(0f, r.top - h - dp(4f))
        val box = RectF(left, top, left + w, top + h)
        fill.color = if (dark) 0xFF55565C.toInt() else white
        c.drawRoundRect(box, dp(10f), dp(10f), fill)
        tp.color = fg()
        tp.textSize = dp(28f)
        c.drawText(label(p.first), box.centerX(), box.centerY() + tp.textSize * 0.35f, tp)
    }

    private fun drawPopup(c: Canvas, items: List<String>) {
        val h = dp(50f)
        val box = RectF(popupLeft, popupTop, popupLeft + popupCell * items.size, popupTop + h)
        fill.color = if (dark) 0xFF55565C.toInt() else white
        c.drawRoundRect(box, dp(10f), dp(10f), fill)
        for (i in items.indices) {
            val cell = RectF(
                popupLeft + i * popupCell + dp(2f), popupTop + dp(3f),
                popupLeft + (i + 1) * popupCell - dp(2f), popupTop + h - dp(3f)
            )
            if (i == popupIndex) {
                fill.color = accent
                c.drawRoundRect(cell, dp(8f), dp(8f), fill)
            }
            tp.color = if (i == popupIndex) white else fg()
            tp.textSize = dp(22f)
            c.drawText(items[i], cell.centerX(), cell.centerY() + tp.textSize * 0.35f, tp)
        }
    }

    // ---- touch -------------------------------------------------------------------------------

    private fun keyAt(x: Float, y: Float): Pair<Key, RectF>? {
        val tol = dp(6f)
        var best: Pair<Key, RectF>? = null
        var bestD = Float.MAX_VALUE
        for (p in keys) {
            val r = p.second
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = p }
        }
        return if (bestD <= tol * tol) best else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pid = e.getPointerId(0)
                down(e.getX(0), e.getY(0))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger lands while the first is still down (fast typing): finish the first key.
                if (pressed != null) up(lastX, lastY)
                pid = e.getPointerId(idx)
                down(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pid)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(idx) == pid) up(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun down(x: Float, y: Float) {
        lastX = x
        lastY = y
        curX = x
        val p = keyAt(x, y) ?: return
        pressed = p
        cursorMode = false
        popup = null
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val k = p.first
        when (k.type) {
            KType.DEL -> {
                listener.onDelete()
                handler.postDelayed(repeatDel, 400)
            }
            KType.CHAR -> if (k.alts.isNotEmpty()) handler.postDelayed(longPress, 380)
            KType.SPACE -> handler.postDelayed(startCursor, 380)
            else -> {}
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        lastX = x
        lastY = y
        curX = x
        val pp = popup
        if (pp != null) {
            val i = ((x - popupLeft) / popupCell).toInt().coerceIn(0, pp.size - 1)
            if (i != popupIndex) { popupIndex = i; invalidate() }
            return
        }
        if (cursorMode) {
            val step = dp(14f)
            val n = ((x - lastCursorX) / step).toInt()
            if (n != 0) {
                listener.onCursor(n)
                lastCursorX += n * step
            }
        }
    }

    private fun up(x: Float, y: Float) {
        handler.removeCallbacks(repeatDel)
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(startCursor)
        val p = pressed
        val pp = popup
        val wasCursor = cursorMode
        pressed = null
        popup = null
        cursorMode = false
        if (p != null) {
            val k = p.first
            if (pp != null) {
                emit(pp[popupIndex])
            } else if (!wasCursor) {
                when (k.type) {
                    KType.CHAR -> emit(label(k))
                    KType.SPACE -> listener.onText(" ")
                    KType.SHIFT -> toggleShift()
                    KType.SYMS -> { page = 1; rebuild() }
                    KType.ABC -> { page = 0; rebuild() }
                    KType.PAGE -> { page = if (page == 1) 2 else 1; rebuild() }
                    KType.ENTER -> listener.onEnter()
                    else -> {}
                }
            }
        }
        invalidate()
    }

    private fun cancel() {
        handler.removeCallbacks(repeatDel)
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(startCursor)
        pressed = null
        popup = null
        cursorMode = false
        invalidate()
    }

    private fun emit(s: String) {
        listener.onText(s)
        if (shift == 1) shift = 0
    }

    // Tap = one capital, quick second tap = caps lock, tap again = off.
    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == 2 -> 0
            shift == 1 && now - lastShiftTap < 350 -> 2
            shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
    }

    private fun showPopup() {
        val p = pressed ?: return
        val k = p.first
        val upper = page == 0 && shift != 0
        val items = k.alts.map { ch -> if (upper && ch.isLetter()) ch.uppercase() else ch.toString() }
        if (items.isEmpty()) return
        popupCell = minOf(maxOf(p.second.width(), dp(42f)), width.toFloat() / items.size)
        val total = popupCell * items.size
        popupLeft = (p.second.centerX() - total / 2).coerceIn(0f, maxOf(0f, width - total))
        popupTop = maxOf(0f, p.second.top - dp(54f))
        popup = items
        popupIndex = ((p.second.centerX() - popupLeft) / popupCell).toInt().coerceIn(0, items.size - 1)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }
}
