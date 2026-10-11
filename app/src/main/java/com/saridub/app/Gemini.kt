package com.saridub.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Bubble(val box: List<Double>, val original: String, val translation: String)

/** One spoken line from a video clip, with timing relative to the clip start. */
data class SpeechCue(
    val startMs: Long, val endMs: Long, val speaker: String,
    val gender: String, val emotion: String, val text: String
)

object Gemini {
    private fun endpoint() =
        "https://generativelanguage.googleapis.com/v1beta/models/${Config.GEMINI_MODEL}:generateContent"

    suspend fun generate(parts: JSONArray, json: Boolean): String = withContext(Dispatchers.IO) {
        if (!Config.hasGemini) throw ApiException(ErrKind.MISSING_KEY, "GEMINI_KEY is not set")
        val gen = JSONObject().put("temperature", 0.2)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()

        val (code, resp) = Net.post(endpoint(), body, mapOf("x-goog-api-key" to Config.GEMINI_KEY))
        if (code !in 200..299) {
            val providerMessage = runCatching {
                JSONObject(resp).optJSONObject("error")?.optString("message")
            }.getOrNull()?.takeIf { !it.isNullOrBlank() && it != "null" }
            val detail = providerMessage ?: "Request rejected by Gemini; check key permissions, model access, quota and API enablement."
            throw ApiException(Net.kindFor(code), "Gemini HTTP $code: ${detail.take(220)}", code)
        }

        val root = JSONObject(resp)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val responseParts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val answer = if (responseParts == null) "" else
            (0 until responseParts.length()).mapNotNull { i ->
                responseParts.optJSONObject(i)?.optString("text")?.takeIf { !it.isNullOrBlank() && it != "null" }
            }.joinToString("\n").trim()
        if (answer.isBlank()) {
            val reason = candidate?.optString("finishReason")?.takeIf { !it.isNullOrBlank() } ?: "no text returned"
            throw ApiException(ErrKind.MALFORMED, "Gemini returned no text ($reason)", code)
        }
        answer
    }

    private fun cleanJson(s: String): String =
        s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    suspend fun translateLines(lines: List<String>, target: String): List<String> {
        val prompt = "Translate each subtitle line to $target. Keep it natural and short for on-screen subtitles. " +
                "Return ONLY a JSON array of strings with the same length and order.\n" + JSONArray(lines).toString()
        val arr = JSONArray(cleanJson(generate(JSONArray().put(JSONObject().put("text", prompt)), json = true)))
        return List(arr.length()) { arr.optString(it) }
    }

    /**
     * Sends a short video clip (with its audio) to Gemini. It transcribes speech, skips music and
     * sound effects, gives the speaker a stable id, detects emotion, and returns the Arabic dialogue.
     */
    suspend fun analyzeVideo(mp4: ByteArray, target: String): List<SpeechCue> {
        val prompt = """
            This is audio extracted from a movie or video. Transcribe each audible spoken dialogue line.
            Do not transcribe music or sound effects. Infer speaker identity, perceived voice gender and emotion from the audio only.
            Translate each spoken line naturally into $target. Keep the original order and accurate timing.
            Return ONLY a JSON array. Each item:
            {"start":seconds,"end":seconds,"speaker":"S1","gender":"male or female","emotion":"neutral|happy|sad|angry|shouting|whisper|calm|fear","text":"translated dialogue"}
            Use the same speaker id when the same voice returns. start and end are seconds from the beginning of this audio segment.
        """.trimIndent()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "audio/mp4")
                .put("data", Base64.encodeToString(mp4, Base64.NO_WRAP))))
            .put(JSONObject().put("text", prompt))
        val arr = JSONArray(cleanJson(generate(parts, json = true)))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val text = o.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            SpeechCue(
                startMs = (o.optDouble("start", 0.0) * 1000).toLong(),
                endMs = (o.optDouble("end", 0.0) * 1000).toLong(),
                speaker = o.optString("speaker", "S1"),
                gender = o.optString("gender", "male"),
                emotion = o.optString("emotion", "neutral"),
                text = text
            )
        }
    }

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
