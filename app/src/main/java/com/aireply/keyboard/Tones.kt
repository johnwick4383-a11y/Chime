package com.aireply.keyboard

// One place to add or edit tones. "instruction" is what Claude is told to do, so a new tone
// is just one more line here. Nothing else in the app needs to change.
data class Tone(val id: String, val label: String, val hint: String, val color: Int, val instruction: String)

object Tones {
    val all = listOf(
        Tone("professional", "Professional", "Polite, clear, work-safe", 0xFF1D4ED8.toInt(),
            "polite, clear and professional, suitable for work"),
        Tone("romantic", "Romantic", "Warm, affectionate", 0xFFBE185D.toInt(),
            "warm, affectionate and romantic, sincere rather than cheesy"),
        Tone("funny", "Funny", "Light, playful jokes", 0xFFB45309.toInt(),
            "funny and playful with a light joke"),
        Tone("sarcastic", "Sarcastic", "Dry, tongue in cheek", 0xFF6D28D9.toInt(),
            "dry and sarcastic in a friendly way, never hurtful"),
        Tone("friendly", "Friendly", "Casual and upbeat", 0xFF0F766E.toInt(),
            "casual, friendly and upbeat"),
        Tone("apologetic", "Apologetic", "Sorry for the delay", 0xFF475569.toInt(),
            "apologetic and sincere, acknowledging any delay or mistake")
    )

    fun byId(id: String?): Tone = all.firstOrNull { it.id == id } ?: all[0]
}
