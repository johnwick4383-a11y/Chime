package com.aireply.keyboard

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// The keyboard itself. Android runs this service whenever a text box needs typing.
// Layout: a toolbar row (AI type, emoji, clipboard, voice, switch keyboard, settings) above the keys.
class AiKeyboardService : InputMethodService(), KeyboardListener {

    private lateinit var kb: KeyboardView
    private lateinit var toolbar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var aiBtn: View
    private lateinit var theme: Theme
    private var dark = false

    // Recent copies, newest first. Only kept in memory while the keyboard process is alive.
    val clips = ArrayList<String>()

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener { rememberClip() }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        theme = Theme(dark)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.bg)

        kb = KeyboardView(this, this)
        kb.dark = dark
        kb.numberRow = getSharedPreferences("kb", MODE_PRIVATE).getBoolean("numrow", false)

        toolbar = buildToolbar()
        content = FrameLayout(this)
        content.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun tool(label: String, size: Float, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = size
        t.gravity = Gravity.CENTER
        t.setTextColor(theme.fg)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), dp(3), dp(6), dp(3))

        val ai = pill(this, "✨ AI", theme.accent, theme.white, 14f)
        ai.setOnClickListener { openPanel(AiPanel(this, theme).view) }
        aiBtn = ai
        val aiLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        aiLp.rightMargin = dp(6)
        bar.addView(ai, aiLp)

        val icons = listOf(
            tool("😀", 20f) { openPanel(EmojiPanel(this, theme).view) },
            tool("📋", 20f) { showClipboard() },
            tool("🎤", 20f) { voice() },
            tool("🌐", 20f) { pickKeyboard() },
            tool("⚙️", 20f) { openSettings() }
        )
        for (v in icons) bar.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return bar
    }

    // ---- panels --------------------------------------------------------------------------------

    private fun openPanel(v: View) {
        val h = if (kb.height > 0) kb.height + toolbar.height else dp(280)
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        toolbar.visibility = View.GONE
    }

    fun showClipboard() {
        rememberClip()
        openPanel(ClipPanel(this, theme).view)
    }

    fun showKeyboard() {
        if (!::kb.isInitialized) return
        content.removeAllViews()
        content.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        toolbar.visibility = View.VISIBLE
    }

    private fun voice() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val v = imm.enabledInputMethodList.firstOrNull {
                it.id.contains("voice", true) || it.id.contains("speech", true)
            }
            if (v != null && Build.VERSION.SDK_INT >= 28) {
                switchInputMethod(v.id)
            } else {
                Toast.makeText(this, "Turn on Google voice typing in Settings > Keyboard list.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Voice typing is not available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun openSettings() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---- text field lifecycle ----------------------------------------------------------------

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::kb.isInitialized) return
        showKeyboard()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // No AI button in password boxes: nothing there is ever read or sent.
        aiBtn.visibility = if (password) View.GONE else View.VISIBLE

        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        kb.enterLabel = if (noAction) "" else when (action) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> ""
        }
        val wantNumRow = getSharedPreferences("kb", MODE_PRIVATE).getBoolean("numrow", false)
        if (kb.numberRow != wantNumRow) kb.numberRow = wantNumRow
        kb.reset()
        autoCap()
    }

    private fun autoCap() {
        val ei = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        if ((ei.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            kb.setAutoShift(false)
            return
        }
        kb.setAutoShift(ic.getCursorCapsMode(ei.inputType) != 0)
    }

    // ---- KeyboardListener ----------------------------------------------------------------------

    override fun onText(s: String) {
        currentInputConnection?.commitText(s, 1)
        autoCap()
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) {
            ic.commitText("", 1)
        } else if (!ic.deleteSurroundingTextInCodePoints(1, 0)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        autoCap()
    }

    override fun onEnter() {
        val ei = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        if (ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    override fun onCursor(steps: Int) {
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        for (i in 0 until Math.abs(steps)) sendDownUpKeyEvents(code)
    }

    // ---- helpers used by the panels ------------------------------------------------------------

    fun insert(text: String, replaceAll: Boolean) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (replaceAll) ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        autoCap()
    }

    // The text in the box the user is typing in (used as the idea or the draft to rewrite).
    fun draft(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(1000, 0) ?: ""
        val after = ic.getTextAfterCursor(1000, 0) ?: ""
        return (before.toString() + after.toString()).trim()
    }

    // The message the user copied. Only read when the AI or clipboard panel is open.
    fun latestClip(): String {
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip
            if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(this).toString().trim()
            else (clips.firstOrNull() ?: "")
        } catch (e: Exception) {
            clips.firstOrNull() ?: ""
        }
    }

    private fun rememberClip() {
        val text = latestClip()
        if (text.isNotBlank() && (clips.isEmpty() || clips[0] != text)) {
            clips.add(0, text)
            while (clips.size > 20) clips.removeAt(clips.size - 1)
        }
    }
}
