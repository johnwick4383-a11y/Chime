package com.aireply.keyboard

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * The heart of the app. Two overlay windows are drawn by this service:
 *  1. the small round "AI" bubble (always on top, draggable), and
 *  2. the reply sheet (opens when the bubble is tapped).
 *
 * Flow: tap bubble -> read chat text on screen -> user picks message + tone -> Claude call
 * (background thread) -> user taps a reply -> reply is typed into the message box.
 *
 * Nothing is read until the bubble is tapped, and nothing leaves the phone except the few
 * messages shown in the sheet, sent to the Claude API.
 */
class ReplyService : AccessibilityService() {

    private data class Msg(val text: String, val top: Int, val mine: Boolean)

    companion object {
        private const val TAG = "AIReply" // filter logcat with this tag when debugging
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var sheet: View? = null
    private var sheetParams: WindowManager.LayoutParams? = null

    // Captured when the bubble is tapped, because opening the sheet can change window focus.
    private var targetField: AccessibilityNodeInfo? = null
    private var chat: List<Msg> = emptyList()
    private var selectedIndex = 0
    private var tone: Tone? = null // no default: the user picks a tone for every chat

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "service connected")
        showBubble()
    }

    // We do not react to events; we only read the screen on demand.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        closeSheet()
        removeBubble()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val v = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0F766E.toInt())
            }
            elevation = dp(6).toFloat()
            contentDescription = "AI Reply"
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(4)
            y = resources.displayMetrics.heightPixels / 3
        }

        // Drag to move, tap to open. A move smaller than the touch slop counts as a tap.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) safeOpenSheet()
                    true
                }
                else -> false
            }
        }
        wm.addView(v, p)
        bubble = v
        bubbleParams = p
        Log.d(TAG, "bubble added")
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble = null
    }

    // ------------------------------------------------------------ reading the screen

    // Any failure is logged and shown as a toast, so a problem is never silent.
    private fun safeOpenSheet() {
        try {
            openSheet()
        } catch (e: Exception) {
            Log.e(TAG, "openSheet failed", e)
            toast("AI Reply error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun openSheet() {
        Log.d(TAG, "bubble tapped")
        if (sheet != null) return
        val root = rootInActiveWindow
        if (root == null) {
            toast("Can't read this screen")
            return
        }
        val found = ArrayList<Msg>()
        collect(root, found, resources.displayMetrics.widthPixels)
        targetField = findInput(root)

        // Top-to-bottom order, drop repeats, keep the last few lines.
        chat = found.sortedBy { it.top }
            .fold(ArrayList<Msg>()) { acc, m -> if (acc.lastOrNull()?.text != m.text) acc.add(m); acc }
            .takeLast(10)

        Log.d(TAG, "found " + chat.size + " text lines on screen")
        // Default: the newest message that looks like it came from the other person.
        // -1 means "write a new message" (also the case when no chat text was found).
        selectedIndex = chat.indexOfLast { !it.mine }
        tone = null
        buildSheet()
    }

    // Walk the view tree and keep visible, non-editable text. Our own windows are skipped.
    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Msg>, screenW: Int) {
        if (node == null) return
        if (node.isVisibleToUser && node.packageName?.toString() != packageName) {
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length >= 2 && !node.isEditable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                // Heuristic: outgoing bubbles hug the right edge, incoming ones the left.
                val mine = r.left > (screenW - r.right) + dp(8)
                out.add(Msg(t, r.top, mine))
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, screenW)
    }

    // The text box to fill: the focused input if there is one, else the lowest editable field.
    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isEditable && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.top > bestTop) { best = n; bestTop = r.top }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    // --------------------------------------------------------------------- sheet UI

    private fun buildSheet() {
        val teal = 0xFF0F766E.toInt()
        val ink = 0xFF1B2321.toInt()
        val muted = 0xFF5B6663.toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            }
        }

        fun label(s: String, size: Int = 13, bold: Boolean = true, color: Int = muted) = TextView(this).apply {
            text = s; textSize = size.toFloat(); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        fun box(strokeColor: Int, fill: Int) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(fill)
            setStroke(dp(1), strokeColor)
        }

        fun lp(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

        // Title row
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(label("Reply with Claude", 18, true, ink), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        title.addView(TextView(this).apply {
            text = "Close"; textSize = 14f; setTextColor(teal); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(4), dp(12))
            setOnClickListener { closeSheet() }
        })
        col.addView(title)

        // Message picker: the last few messages, tap to choose which one to reply to.
        col.addView(label("Replying to (tap to change)"), lp(top = 6))
        val msgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val msgScroll = ScrollView(this).apply { addView(msgBox) }
        col.addView(msgScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)).apply { topMargin = dp(4) })

        val rows = ArrayList<TextView>()
        fun paintRows() {
            rows.forEachIndexed { i, tv ->
                val sel = i - 1 == selectedIndex // rows[0] is the "new message" row
                tv.background = box(if (sel) teal else 0xFFDAD5CC.toInt(), if (sel) 0xFFF1F8F6.toInt() else Color.WHITE)
            }
        }
        // First entry: write a brand-new message (no reply), e.g. a good morning note, info or blessing.
        val composeRow = TextView(this).apply {
            text = "Write a new message (no reply)"
            textSize = 14f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { selectedIndex = -1; paintRows() }
        }
        rows.add(composeRow)
        col.addView(composeRow, lp(top = 4))
        chat.forEachIndexed { i, m ->
            val tv = TextView(this).apply {
                text = (if (m.mine) "Me: " else "Them: ") + m.text
                textSize = 14f; setTextColor(ink)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { selectedIndex = i; paintRows() }
            }
            rows.add(tv)
            msgBox.addView(tv, lp(top = 4))
        }
        paintRows()
        msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }

        // Tone dropdown (a button that expands a list; avoids Spinner popups inside an overlay).
        col.addView(label("Tone"), lp(top = 10))
        val toneBtn = TextView(this).apply {
            textSize = 15f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = box(0xFF9AA39F.toInt(), Color.WHITE)
        }
        fun paintTone() { toneBtn.text = (tone?.label ?: "Choose a tone") + "   ▾" }
        paintTone()
        col.addView(toneBtn, lp(top = 4))

        val toneList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        // Only the tones switched on in the setup screen are listed.
        Tones.all.filter { it.id in Prefs.enabled(this) }.ifEmpty { Tones.all }.forEach { t ->
            toneList.addView(TextView(this).apply {
                text = t.label + "  -  " + t.hint
                textSize = 14f; setTextColor(ink)
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setOnClickListener {
                    tone = t
                    paintTone()
                    toneList.visibility = View.GONE
                }
            }, lp())
        }
        col.addView(toneList, lp(top = 2))
        toneBtn.setOnClickListener {
            toneList.visibility = if (toneList.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Optional context. Tapping it makes the sheet focusable so the keyboard can appear.
        val extra = EditText(this).apply {
            hint = "Extra context (optional), e.g. I'm running late"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = box(0xFF9AA39F.toInt(), Color.WHITE)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) setSheetFocusable(true)
                false
            }
        }
        col.addView(extra, lp(top = 10))

        // Results area
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val go = TextView(this).apply {
            text = "Generate replies"
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(teal) }
        }
        go.setOnClickListener {
            hideKeyboard(extra)
            setSheetFocusable(false)
            if (tone == null) {
                toneList.visibility = View.VISIBLE
                showNote(results, "Choose a tone for this chat first.", 0xFFB42318.toInt())
            } else {
                toneList.visibility = View.GONE
                generate(results, extra.text.toString())
            }
        }
        col.addView(go, lp(top = 10))
        col.addView(results, lp(top = 8))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(col, p)
        sheet = col
        sheetParams = p
        bubble?.visibility = View.GONE
        Log.d(TAG, "sheet shown")
    }

    // Window flag toggle: the sheet is NOT focusable by default so it never steals focus from the
    // chat app. Only while typing in the context box do we allow focus (needed for the keyboard).
    private fun setSheetFocusable(focusable: Boolean) {
        val v = sheet ?: return
        val p = sheetParams ?: return
        p.flags = if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(v, p)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun closeSheet() {
        sheet?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        sheet = null
        sheetParams = null
        bubble?.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------- Claude + insert

    private fun generate(results: LinearLayout, extra: String) {
        results.removeAllViews()
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            showNote(results, "Add your Claude API key in the AI Reply app first.", 0xFFB42318.toInt())
            return
        }
        val chosen = tone ?: return
        if (selectedIndex < 0 && extra.isBlank()) {
            showNote(results, "Say what the message should be about in the context box.", 0xFFB42318.toInt())
            return
        }
        showNote(results, "Claude is writing ${chosen.label.lowercase()} messages...", 0xFF5B6663.toInt())

        val target = if (selectedIndex >= 0) chat[selectedIndex].text else ""
        val convo = chat.joinToString("\n") { (if (it.mine) "Me: " else "Them: ") + it.text }
        val model = Prefs.model(this)

        // Network on a background thread; results posted back to the main thread.
        Thread {
            try {
                val replies = ClaudeClient.suggest(key, model, convo, target, chosen, extra)
                main.post { if (sheet != null) showReplies(results, replies) }
            } catch (e: Exception) {
                main.post {
                    if (sheet != null) {
                        results.removeAllViews()
                        showNote(results, e.message ?: "Something went wrong", 0xFFB42318.toInt())
                    }
                }
            }
        }.start()
    }

    private fun showNote(results: LinearLayout, msg: String, color: Int) {
        results.removeAllViews()
        results.addView(TextView(this).apply { text = msg; textSize = 14f; setTextColor(color) })
    }

    private fun showReplies(results: LinearLayout, replies: List<String>) {
        results.removeAllViews()
        results.addView(TextView(this).apply {
            text = "Tap a reply to put it in the typing bar"
            textSize = 13f; setTextColor(0xFF5B6663.toInt()); typeface = Typeface.DEFAULT_BOLD
        })
        replies.forEach { r ->
            val tv = TextView(this).apply {
                text = r
                textSize = 15f; setTextColor(0xFF1B2321.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFF1F8F6.toInt())
                    setStroke(dp(1), 0xFFBFD9D4.toInt())
                }
                setOnClickListener { insert(r) }
            }
            results.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
    }

    // Close the sheet first so focus returns to the chat app, then type into its message box.
    // If the app refuses (some fields block this), the reply goes to the clipboard instead.
    private fun insert(text: String) {
        closeSheet()
        main.postDelayed({
            var ok = false
            val n = targetField
            if (n != null && n.refresh()) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            if (!ok) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI reply", text))
                toast("Reply copied. Long-press the message box and paste.")
            }
        }, 250)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
