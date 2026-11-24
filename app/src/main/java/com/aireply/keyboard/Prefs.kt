package com.aireply.keyboard

import android.content.Context

// Settings live in app-private storage (other apps cannot read it).
// For a personal build this is fine; for a public release, move the key behind a backend.
object Prefs {
    const val DEFAULT_MODEL = "claude-haiku-5-5" // fast and cheap; change in the app if you like

    private fun sp(c: Context) = c.getSharedPreferences("aireply", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("apiKey", "") ?: ""
    fun model(c: Context): String = sp(c).getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
    fun toneId(c: Context): String = sp(c).getString("tone", "professional") ?: "professional"

    fun save(c: Context, apiKey: String, model: String) {
        sp(c).edit().putString("apiKey", apiKey.trim()).putString("model", model.trim()).apply()
    }

    fun saveTone(c: Context, id: String) {
        sp(c).edit().putString("tone", id).apply()
    }
}
