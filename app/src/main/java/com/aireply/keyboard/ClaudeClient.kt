package com.aireply.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Talks to the Claude Messages API. Blocking: always call it from a background thread.
object ClaudeClient {

    private const val SYSTEM_PROMPT =
        "You write message drafts that the user will send in a chat app. Usually they are replies; " +
        "if there is no message to answer, they are new messages. " +
        "Write as the user, in first person. Match the language of the chat or of the user's context. " +
        "Keep each draft short, like a real chat message (up to about 3 sentences). " +
        "Output ONLY a JSON array of exactly 3 different strings. No explanations, no markdown."

    fun suggest(
        apiKey: String,
        model: String,
        conversation: String,
        target: String,
        tone: Tone,
        extra: String
    ): List<String> {
        val prompt = buildString {
            if (conversation.isNotBlank()) {
                append("Recent chat (Me = the user, Them = the other person; labels are a best guess):\n")
                append(conversation).append("\n\n")
            }
            if (target.isBlank()) {
                append("There is no message to answer. Write 3 different NEW messages the user can send, based on the context below.\n\n")
            } else {
                append("Write 3 replies to this message from Them:\n\"").append(target).append("\"\n\n")
            }
            append("Tone: ").append(tone.instruction).append(".\n")
            if (extra.isNotBlank()) append("Extra context from the user: ").append(extra.trim()).append("\n")
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 700)
            .put("system", SYSTEM_PROMPT)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
            )

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text.take(200)
                }
                throw IOException("Claude error $code: $msg")
            }
            return parseReplies(extractText(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(responseJson: String): String {
        val content = JSONObject(responseJson).getJSONArray("content")
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") return block.getString("text")
        }
        throw IOException("Claude sent no text")
    }

    // Claude is asked for a JSON array. If it adds stray text around it, cut the array out;
    // if it is not valid JSON at all, fall back to one reply per line.
    private fun parseReplies(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(raw.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) out.add(s)
                }
                if (out.isNotEmpty()) return out.take(3)
            } catch (e: Exception) {
                // fall through to the line-based fallback
            }
        }
        val lines = raw.lines().map { it.trim().trim('"', ',', '-', '*') }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw IOException("Claude sent an empty reply")
        return lines.take(3)
    }
}
