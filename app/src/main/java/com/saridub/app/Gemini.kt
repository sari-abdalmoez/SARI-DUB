package com.saridub.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Bubble(val box: List<Double>, val original: String, val translation: String)

/** Gemini client. Uses the flash-lite model: cheap in tokens, fast, good for batch translation. */
object Gemini {
    private fun endpoint() =
        "https://generativelanguage.googleapis.com/v1beta/models/${Config.GEMINI_MODEL}:generateContent"

    suspend fun generate(parts: JSONArray, json: Boolean): String = withContext(Dispatchers.IO) {
        val gen = JSONObject().put("temperature", 0.2)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()

        var (code, resp) = Net.post(endpoint(), body, mapOf("x-goog-api-key" to Config.GEMINI_KEY))
        if (code == 401 || code == 403) {
            // Some credential formats need a Bearer header instead of the API-key header.
            val retry = Net.post(endpoint(), body, mapOf("Authorization" to "Bearer ${Config.GEMINI_KEY}"))
            code = retry.first
            resp = retry.second
        }
        if (code !in 200..299) throw IllegalStateException("Gemini HTTP $code: ${resp.take(160)}")

        JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun cleanJson(s: String): String =
        s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    /** Batch subtitle translation. Returns one line per input line, same order. */
    suspend fun translateLines(lines: List<String>, target: String): List<String> {
        val prompt = "Translate each subtitle line to $target. Keep it natural and short enough for on-screen subtitles. " +
                "Return ONLY a JSON array of strings with the same length and order.\n" + JSONArray(lines).toString()
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        val arr = JSONArray(cleanJson(generate(parts, json = true)))
        return List(arr.length()) { arr.optString(it) }
    }

    /** One call per manga page: detects bubbles, their boxes, the original text and the translation. */
    suspend fun mangaBubbles(jpeg: ByteArray, target: String): List<Bubble> {
        val prompt = """
            This is a manga page. Find every speech or narration bubble that contains readable text.
            Return ONLY a JSON array. Each item: {"box":[ymin,xmin,ymax,xmax],"original":"...","translation":"..."}
            box values are integers from 0 to 1000, tightly around the text inside the bubble.
            translation is the natural $target dialogue for that text. Skip panels without text.
        """.trimIndent()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", prompt))
        val arr = JSONArray(cleanJson(generate(parts, json = true)))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val b = o.optJSONArray("box") ?: return@mapNotNull null
            if (b.length() < 4) return@mapNotNull null
            Bubble(List(4) { b.getDouble(it) }, o.optString("original"), o.optString("translation"))
        }
    }
}
