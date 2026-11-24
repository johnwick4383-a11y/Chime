package com.aireply.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Setup screen. Layout is built in code (no XML) to keep the project tiny and easy to follow:
// step 1 turn on the accessibility service, step 2 paste the Claude key, step 3 pick a default tone.
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private val toneButtons = HashMap<String, TextView>()

    private val teal = 0xFF0F766E.toInt()
    private val ink = 0xFF1B2321.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFECE8E1.toInt())
            addView(col)
        }
        setContentView(scroll)

        col.addView(text("Set up AI Reply", 26, true, ink))
        col.addView(text("Three quick steps. You only do this once.", 15, false, 0xFF47524F.toInt()), lp(top = 4))

        status = text("", 14, true, ink)
        col.addView(status, lp(top = 16))

        // Step 1
        col.addView(card("1  Let it read the chat on screen",
            "Turn on the AI Reply accessibility service. It only reads the screen when you tap the floating AI button.") { c ->
            c.addView(button("Open Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, lp(top = 8))
            c.addView(text("If the switch is greyed out (Android 13+ when installed from an APK): open App info, tap the three dots at the top right, choose Allow restricted settings, then go back.", 13, false, 0xFF47524F.toInt()), lp(top = 8))
            c.addView(button("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, lp(top = 8))
        }, lp(top = 12))

        // Step 2
        keyField = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        modelField = EditText(this).apply {
            hint = "Model"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.model(this@MainActivity))
        }
        col.addView(card("2  Connect Claude",
            "Paste your Anthropic API key. It is stored only on this phone. Model is editable, e.g. a bigger model for better replies.") { c ->
            c.addView(keyField, lp(top = 8))
            c.addView(modelField, lp(top = 4))
            c.addView(button("Save") {
                Prefs.save(this, keyField.text.toString(), modelField.text.toString())
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }, lp(top = 8))
        }, lp(top = 12))

        // Step 3
        col.addView(card("3  Pick your usual tone",
            "This is the starting tone in the reply sheet. You can change it every time.") { c ->
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // Two tone chips per row.
            Tones.all.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { t ->
                    val chip = text(t.label, 14, true, ink).apply {
                        gravity = android.view.Gravity.CENTER
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        setOnClickListener {
                            Prefs.saveTone(this@MainActivity, t.id)
                            refreshTones()
                        }
                    }
                    toneButtons[t.id] = chip
                    row.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                wrap.addView(row, lp(top = 6))
            }
            c.addView(wrap, lp(top = 4))
        }, lp(top = 12))

        col.addView(text("How to use: open any chat, tap the round AI button on the edge of the screen, choose the message and tone, tap Generate, then tap a reply. It appears in the message box.", 14, false, 0xFF47524F.toInt()), lp(top = 16))

        refreshTones()
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "Service is ON. The AI button should be visible over your apps."
        else "Service is OFF. Do step 1 first."
        status.setTextColor(if (on) teal else 0xFFB42318.toInt())
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    private fun refreshTones() {
        val current = Prefs.toneId(this)
        for ((id, view) in toneButtons) {
            val selected = id == current
            view.setTextColor(if (selected) Color.WHITE else ink)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (selected) setColor(teal) else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), 0xFF9AA39F.toInt())
                }
            }
        }
    }

    // ---- tiny view helpers ----
    private fun text(s: String, sp: Int, bold: Boolean, color: Int) = TextView(this).apply {
        text = s
        textSize = sp.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(teal)
        }
        setOnClickListener { onClick() }
    }

    private fun card(title: String, body: String, fill: (LinearLayout) -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
            }
        }
        c.addView(text(title, 16, true, ink))
        c.addView(text(body, 14, false, 0xFF47524F.toInt()), lp(top = 4))
        fill(c)
        return c
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
}
