package com.aireply.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Colours shared by every panel (light and dark follow the phone's setting).
class Theme(val dark: Boolean) {
    val bg: Int = if (dark) 0xFF1B1C1F.toInt() else 0xFFE9EAEE.toInt()
    val card: Int = if (dark) 0xFF2F3035.toInt() else 0xFFFFFFFF.toInt()
    val fg: Int = if (dark) 0xFFFFFFFF.toInt() else 0xFF1B1C1F.toInt()
    val sub: Int = if (dark) 0xFFB0B3BC.toInt() else 0xFF5B5F6B.toInt()
    val accent: Int = 0xFF3E6BFF.toInt()
    val white: Int = 0xFFFFFFFF.toInt()
}

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun pill(ctx: Context, text: String, fillColor: Int, textColor: Int, size: Float): TextView {
    val t = TextView(ctx)
    t.text = text
    t.setTextColor(textColor)
    t.textSize = size
    t.gravity = Gravity.CENTER
    t.setPadding(ctx.dpi(12), ctx.dpi(7), ctx.dpi(12), ctx.dpi(7))
    val g = GradientDrawable()
    g.setColor(fillColor)
    g.cornerRadius = ctx.dpi(16).toFloat()
    t.background = g
    return t
}

fun rightMargin(ctx: Context, dp: Int): LinearLayout.LayoutParams {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.rightMargin = ctx.dpi(dp)
    return lp
}

fun panelHeader(ctx: Context, th: Theme, title: String, onClose: () -> Unit, vararg extra: View): LinearLayout {
    val head = LinearLayout(ctx)
    head.gravity = Gravity.CENTER_VERTICAL
    val tv = TextView(ctx)
    tv.text = title
    tv.setTextColor(th.fg)
    tv.textSize = 17f
    tv.typeface = Typeface.DEFAULT_BOLD
    head.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    for (v in extra) head.addView(v, rightMargin(ctx, 6))
    val close = pill(ctx, "ABC", th.card, th.fg, 14f)
    close.setOnClickListener { onClose() }
    head.addView(close)
    return head
}

// ---- AI type panel ---------------------------------------------------------------------------
// Three jobs: reply to a message you copied, write something new, or rewrite what you typed.
// A tone must be picked every time (nothing is pre-selected), and the text is only sent to Claude
// when you tap Generate.
class AiPanel(private val svc: AiKeyboardService, private val th: Theme) {
    private val ctx: Context = svc
    private val ui = Handler(Looper.getMainLooper())
    private var mode = 0 // 0 reply, 1 write new, 2 rewrite
    private var tone: Tone? = null
    private var busy = false
    private val modeRow = LinearLayout(ctx)
    private val toneRow = LinearLayout(ctx)
    private val info = TextView(ctx)
    private val status = TextView(ctx)
    private val results = LinearLayout(ctx)
    val view: View

    init {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dpi(10), ctx.dpi(8), ctx.dpi(10), ctx.dpi(8))
        col.addView(panelHeader(ctx, th, "✨ AI type", { svc.showKeyboard() }))

        modeRow.setPadding(0, ctx.dpi(8), 0, 0)
        col.addView(modeRow)

        val toneScroll = HorizontalScrollView(ctx)
        toneScroll.isHorizontalScrollBarEnabled = false
        toneRow.setPadding(0, ctx.dpi(8), 0, 0)
        toneScroll.addView(toneRow)
        col.addView(toneScroll)

        info.setTextColor(th.sub)
        info.textSize = 13f
        info.setPadding(0, ctx.dpi(8), 0, ctx.dpi(6))
        info.setOnClickListener { updateInfo() }
        col.addView(info)

        val gen = pill(ctx, "Generate", th.accent, th.white, 15f)
        gen.setOnClickListener { generate() }
        col.addView(gen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status.setTextColor(th.sub)
        status.textSize = 13f
        status.setPadding(0, ctx.dpi(6), 0, ctx.dpi(4))
        col.addView(status)

        results.orientation = LinearLayout.VERTICAL
        col.addView(results)

        val sv = ScrollView(ctx)
        sv.setBackgroundColor(th.bg)
        sv.addView(col)
        view = sv
        renderModes()
        renderTones()
        updateInfo()
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        val names = listOf("Reply", "Write new", "Rewrite")
        names.forEachIndexed { i, n ->
            val on = i == mode
            val p = pill(ctx, n, if (on) th.accent else th.card, if (on) th.white else th.fg, 13f)
            p.setOnClickListener {
                mode = i
                renderModes()
                updateInfo()
                results.removeAllViews()
                status.text = ""
            }
            modeRow.addView(p, rightMargin(ctx, 6))
        }
    }

    private fun renderTones() {
        toneRow.removeAllViews()
        val ids = Prefs.enabled(ctx)
        val list = Tones.all.filter { it.id in ids }
        val cur = tone
        if (cur != null && cur.id !in ids) tone = null
        if (list.isEmpty()) {
            val t = TextView(ctx)
            t.text = "No tones turned on. Open the AI Keyboard app and pick some."
            t.setTextColor(th.sub)
            t.textSize = 13f
            toneRow.addView(t)
            return
        }
        for (t in list) {
            val on = tone?.id == t.id
            val p = pill(ctx, t.label, if (on) t.color else th.card, if (on) th.white else th.fg, 13f)
            p.setOnClickListener { tone = t; renderTones() }
            toneRow.addView(p, rightMargin(ctx, 6))
        }
    }

    private fun updateInfo() {
        val clip = svc.latestClip()
        val draft = svc.draft()
        info.text = when (mode) {
            0 -> if (clip.isBlank()) "Copy the message you want to answer (long-press it, then Copy), then tap here."
                 else "Replying to: " + clip.take(160) + "  (tap to refresh)"
            1 -> "Idea from your typed text: " +
                 (if (draft.isBlank()) "none. Type a few words in the chat box first, or just generate." else draft.take(120))
            else -> if (draft.isBlank()) "Type your draft in the chat box, then come back to rewrite it."
                    else "Rewriting: " + draft.take(160)
        }
    }

    private fun generate() {
        if (busy) return
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) { status.text = "Add your Claude API key in the AI Keyboard app first."; return }
        val t = tone
        if (t == null) { status.text = "Pick a tone first."; return }
        val clip = svc.latestClip()
        val draft = svc.draft()
        val conversation: String
        val target: String
        val extra: String
        when (mode) {
            0 -> {
                if (clip.isBlank()) { status.text = "Copy the message first, then tap Generate."; return }
                conversation = "Them: " + clip
                target = clip
                extra = draft
            }
            1 -> {
                conversation = ""
                target = ""
                extra = draft
            }
            else -> {
                if (draft.isBlank()) { status.text = "Type your draft in the chat box first."; return }
                conversation = ""
                target = ""
                extra = "Rewrite this draft the user wrote, keeping its meaning: " + draft
            }
        }
        busy = true
        status.text = "Thinking..."
        results.removeAllViews()
        val m = mode
        Thread {
            var out: List<String> = emptyList()
            var err: String? = null
            try {
                out = ClaudeClient.suggest(key, Prefs.model(ctx), conversation, target, t, extra)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val msg = err
            ui.post {
                busy = false
                if (msg != null) {
                    status.text = msg
                } else {
                    status.text = "Tap one to use it"
                    show(out, m)
                }
            }
        }.start()
    }

    private fun show(list: List<String>, m: Int) {
        results.removeAllViews()
        for (text in list) {
            val card = TextView(ctx)
            card.text = text
            card.setTextColor(th.fg)
            card.textSize = 15f
            card.setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10))
            val g = GradientDrawable()
            g.setColor(th.card)
            g.cornerRadius = ctx.dpi(12).toFloat()
            card.background = g
            card.setOnClickListener {
                svc.insert(text, m == 2)
                svc.showKeyboard()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = ctx.dpi(6)
            results.addView(card, lp)
        }
    }
}

// ---- Clipboard panel -------------------------------------------------------------------------
// Shows what was copied while the keyboard was running; tap one to paste it.
class ClipPanel(private val svc: AiKeyboardService, private val th: Theme) {
    private val ctx: Context = svc
    val view: View

    init {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dpi(10), ctx.dpi(8), ctx.dpi(10), ctx.dpi(8))
        val clear = pill(ctx, "Clear", th.card, th.fg, 14f)
        clear.setOnClickListener { svc.clips.clear(); svc.showClipboard() }
        col.addView(panelHeader(ctx, th, "Clipboard", { svc.showKeyboard() }, clear))
        if (svc.clips.isEmpty()) {
            val t = TextView(ctx)
            t.text = "Nothing copied yet. Copy some text and it shows up here."
            t.setTextColor(th.sub)
            t.textSize = 14f
            t.setPadding(0, ctx.dpi(14), 0, 0)
            col.addView(t)
        }
        for (c in svc.clips) {
            val card = TextView(ctx)
            card.text = c
            card.maxLines = 3
            card.setTextColor(th.fg)
            card.textSize = 14f
            card.setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10))
            val g = GradientDrawable()
            g.setColor(th.card)
            g.cornerRadius = ctx.dpi(12).toFloat()
            card.background = g
            card.setOnClickListener { svc.insert(c, false); svc.showKeyboard() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = ctx.dpi(6)
            col.addView(card, lp)
        }
        val sv = ScrollView(ctx)
        sv.setBackgroundColor(th.bg)
        sv.addView(col)
        view = sv
    }
}

// ---- Emoji panel -----------------------------------------------------------------------------
class EmojiPanel(private val svc: AiKeyboardService, private val th: Theme) {
    private val ctx: Context = svc
    private val body = LinearLayout(ctx)
    val view: View

    private fun cp(c: Int): String {
        val s = String(Character.toChars(c))
        return if (c < 0x1F000) s + "️" else s
    }

    private fun range(a: Int, b: Int): List<String> = (a..b).map { cp(it) }

    private val cats: List<Pair<String, List<String>>> = listOf(
        cp(0x1F600) to range(0x1F600, 0x1F64F),
        cp(0x1F44D) to (range(0x1F446, 0x1F450) + listOf(0x1F64C, 0x1F64F, 0x1F4AA, 0x1F91D).map { cp(it) }),
        cp(0x2764) to (range(0x1F493, 0x1F49F) + listOf(0x2764, 0x1F9E1, 0x1F5A4).map { cp(it) }),
        cp(0x1F436) to range(0x1F400, 0x1F43E),
        cp(0x1F354) to range(0x1F345, 0x1F37C),
        cp(0x1F389) to listOf(
            0x1F389, 0x1F38A, 0x1F381, 0x1F388, 0x1F525, 0x2728, 0x2B50, 0x1F4AF, 0x1F31F, 0x1F308,
            0x2600, 0x1F319, 0x26A1, 0x1F680, 0x1F697, 0x2708, 0x1F3E0, 0x1F3B5, 0x1F4F1, 0x1F4A1,
            0x1F4DA, 0x1F451, 0x1F48E, 0x1F3C6, 0x26BD, 0x1F3C0, 0x1F4A9, 0x1F4A4
        ).map { cp(it) }
    )

    init {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(th.bg)
        col.setPadding(ctx.dpi(8), ctx.dpi(8), ctx.dpi(8), ctx.dpi(4))

        val tabs = LinearLayout(ctx)
        cats.forEachIndexed { i, c ->
            val p = pill(ctx, c.first, th.card, th.fg, 18f)
            p.setOnClickListener { fill(i) }
            tabs.addView(p, rightMargin(ctx, 6))
        }
        val tabScroll = HorizontalScrollView(ctx)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabs)

        val del = pill(ctx, "⌫", th.card, th.fg, 16f)
        del.setOnClickListener { svc.onDelete() }
        val abc = pill(ctx, "ABC", th.card, th.fg, 14f)
        abc.setOnClickListener { svc.showKeyboard() }
        val head = LinearLayout(ctx)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tabScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(del, rightMargin(ctx, 6))
        head.addView(abc)
        col.addView(head)

        body.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(ctx)
        sv.addView(body)
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view = col
        fill(0)
    }

    private fun fill(i: Int) {
        body.removeAllViews()
        for (chunk in cats[i].second.chunked(8)) {
            val row = LinearLayout(ctx)
            for (e in chunk) {
                val t = TextView(ctx)
                t.text = e
                t.textSize = 24f
                t.gravity = Gravity.CENTER
                t.setPadding(0, ctx.dpi(8), 0, ctx.dpi(8))
                t.setOnClickListener { svc.insert(e, false) }
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            body.addView(row)
        }
    }
}
