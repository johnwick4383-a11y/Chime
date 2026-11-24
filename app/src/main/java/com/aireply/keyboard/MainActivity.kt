package com.aireply.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

// Setup screen. Keyboard first: turn it on, choose it, then add your Claude key and pick tones.
class MainActivity : Activity() {

    private lateinit var status1: TextView
    private lateinit var status2: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(6), 0, dp(2))
        return t
    }

    private fun button(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))

        root.addView(text("AI Keyboard", 26f, true, 0xFF111111.toInt()))
        root.addView(text("A full keyboard with an AI button. Copy a message, tap AI, pick a tone, tap a reply.", 14f, false, 0xFF555555.toInt()))

        // Step 1
        root.addView(text("Step 1: Turn the keyboard on", 17f, true, 0xFF111111.toInt()))
        status1 = text("", 14f, true, 0xFF444444.toInt())
        root.addView(status1)
        root.addView(button("Open keyboard settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(text("Switch on \"AI Keyboard\" in the list and accept the warning. Samsung: Settings > General management > Keyboard list and default.", 13f, false, 0xFF666666.toInt()))

        // Step 2
        root.addView(text("Step 2: Use it", 17f, true, 0xFF111111.toInt()))
        status2 = text("", 14f, true, 0xFF444444.toInt())
        root.addView(status2)
        root.addView(button("Choose keyboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        // Step 3
        root.addView(text("Step 3: Your Claude API key", 17f, true, 0xFF111111.toInt()))
        val key = EditText(this)
        key.hint = "sk-ant-..."
        key.setText(Prefs.apiKey(this))
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(key)
        val model = EditText(this)
        model.hint = "Model"
        model.setText(Prefs.model(this))
        root.addView(model)
        root.addView(button("Save") {
            Prefs.save(this, key.text.toString(), model.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        // Step 4
        root.addView(text("Step 4: Tones you want in the AI panel", 17f, true, 0xFF111111.toInt()))
        root.addView(text("You pick the tone for each chat when you use it.", 13f, false, 0xFF666666.toInt()))
        root.addView(toneChips())

        // Keyboard options
        root.addView(text("Keyboard options", 17f, true, 0xFF111111.toInt()))
        val sw = Switch(this)
        sw.text = "Always show the number row"
        val kbPrefs = getSharedPreferences("kb", MODE_PRIVATE)
        sw.isChecked = kbPrefs.getBoolean("numrow", false)
        sw.setOnCheckedChangeListener { _, on -> kbPrefs.edit().putBoolean("numrow", on).apply() }
        root.addView(sw)

        // Try it
        root.addView(text("Try it", 17f, true, 0xFF111111.toInt()))
        val tryBox = EditText(this)
        tryBox.hint = "Tap here and type"
        tryBox.minLines = 3
        tryBox.gravity = android.view.Gravity.TOP
        root.addView(tryBox)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    private fun toneChips(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        var current = LinearLayout(this)
        var count = 0
        box.addView(current)
        for (tone in Tones.all) {
            if (count == 2) {
                current = LinearLayout(this)
                box.addView(current)
                count = 0
            }
            val chip = TextView(this)
            chip.text = tone.label
            chip.textSize = 14f
            chip.gravity = android.view.Gravity.CENTER
            chip.setPadding(dp(14), dp(10), dp(14), dp(10))
            fun paint() {
                val on = tone.id in Prefs.enabled(this)
                val g = GradientDrawable()
                g.cornerRadius = dp(18).toFloat()
                g.setColor(if (on) tone.color else 0xFFE5E7EB.toInt())
                chip.background = g
                chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
            }
            paint()
            chip.setOnClickListener {
                val set = HashSet<String>(Prefs.enabled(this))
                if (tone.id in set) set.remove(tone.id) else set.add(tone.id)
                Prefs.setEnabled(this, set)
                paint()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            current.addView(chip, lp)
            count++
        }
        return box
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val active = current.startsWith(packageName + "/")
        status1.text = if (enabled) "Keyboard is ON" else "Keyboard is OFF"
        status1.setTextColor(if (enabled) 0xFF15803D.toInt() else 0xFFB91C1C.toInt())
        status2.text = if (active) "AI Keyboard is your current keyboard" else "Not selected yet"
        status2.setTextColor(if (active) 0xFF15803D.toInt() else 0xFFB45309.toInt())
    }
}
