package com.saridub.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** API key entry for the Settings screen. Values are saved on the device only. */
@Composable
fun ApiKeysSettings() {
    var tmdb by remember { mutableStateOf("") }
    var trakt by remember { mutableStateOf("") }
    var gemini by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "مفاتيح الخدمات — TMDB: ${mark(Config.hasTmdb)}  Trakt: ${mark(Config.hasTrakt)}  Gemini: ${mark(Config.hasGemini)}",
            color = TextMain, fontSize = 13.sp
        )
        OutlinedTextField(
            value = tmdb, onValueChange = { tmdb = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TMDB v3 API Key أو Read Access Token") },
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = trakt, onValueChange = { trakt = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Trakt Client ID") },
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = gemini, onValueChange = { gemini = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Google Gemini API key") },
            visualTransformation = PasswordVisualTransformation()
        )

        GradientButton(if (busy) "جاري الفحص…" else "حفظ وفحص المفاتيح", enabled = !busy) {
            scope.launch {
                busy = true
                Config.saveApiKeys(tmdb, trakt, gemini)
                tmdb = ""; trakt = ""; gemini = ""
                report = withContext(Dispatchers.IO) { checkKeys() }
                busy = false
            }
        }
        Text(
            "المفاتيح المحفوظة هنا لها أولوية على مفاتيح النسخة. اترك الحقول فارغة لاستخدام مفاتيح GitHub المضمنة في نسخة البناء.",
            color = TextMid, fontSize = 12.sp
        )
        GradientButton("مسح المفاتيح المحفوظة وفحص مفاتيح النسخة", enabled = !busy) {
            scope.launch {
                busy = true
                Config.clearSavedApiKeys()
                report = withContext(Dispatchers.IO) { checkKeys() }
                busy = false
            }
        }
        if (report.isNotBlank()) {
            Text(report, color = TextMid, fontSize = 12.sp)
        }
    }
}

/** Probes each actual service operation used by the app, without displaying any key value. */
private fun checkKeys(): String {
    val lines = ArrayList<String>()
    lines += "TMDB: " + if (!Config.hasTmdb) {
        "غير مضبوط"
    } else runCatching {
        TmdbApi.get("configuration")
        "يعمل"
    }.getOrElse { Net.describe(it) }

    lines += "Trakt: " + if (!Config.hasTrakt) {
        "غير مضبوط"
    } else runCatching {
        Net.get(
            "https://api.trakt.tv/movies/trending?limit=1",
            mapOf("Content-Type" to "application/json", "trakt-api-version" to "2",
                "trakt-api-key" to Config.TRAKT_CLIENT_ID)
        )
        "يعمل"
    }.getOrElse { Net.describe(it) }

    lines += "Gemini: " + if (!Config.hasGemini) {
        "غير مضبوط"
    } else runCatching { checkGeminiModel() }.getOrElse { Net.describe(it) }

    return lines.joinToString("\n")
}

/** A tiny generation request verifies both the API key and the exact model used by translation. */
private fun checkGeminiModel(): String {
    val body = org.json.JSONObject()
        .put("contents", org.json.JSONArray().put(
            org.json.JSONObject().put("parts",
                org.json.JSONArray().put(org.json.JSONObject().put("text", "Reply with OK only."))
            )
        ))
        .put("generationConfig", org.json.JSONObject().put("maxOutputTokens", 8))
        .toString()

    val (code, response) = Net.post(
        "https://generativelanguage.googleapis.com/v1beta/models/${Config.GEMINI_MODEL}:generateContent",
        body, mapOf("x-goog-api-key" to Config.GEMINI_KEY), timeout = 30000
    )
    if (code !in 200..299) {
        val detail = runCatching {
            org.json.JSONObject(response).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val message = if (detail.isNotBlank()) "Gemini HTTP $code: $detail" else "Gemini HTTP $code"
        throw ApiException(Net.kindFor(code), message, code)
    }
    val text = runCatching {
        org.json.JSONObject(response).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text")
    }.getOrDefault("")
    if (text.isBlank()) throw ApiException(ErrKind.MALFORMED, "Gemini returned no text")
    return "يعمل مع النموذج ${Config.GEMINI_MODEL}"
}
