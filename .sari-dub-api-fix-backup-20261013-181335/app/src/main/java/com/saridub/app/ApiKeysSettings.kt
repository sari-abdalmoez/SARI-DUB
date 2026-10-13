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
            label = { Text("TMDB API Read Access Token") },
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
        GradientButton("حذف المفاتيح المحفوظة", enabled = !busy) {
            Config.clearSavedApiKeys()
            report = "تم حذف المفاتيح المحفوظة على هذا الجهاز."
        }
        if (report.isNotBlank()) {
            Text(report, color = TextMid, fontSize = 12.sp)
        }
    }
}

/** Calls one public endpoint per service. Each call proves the key is accepted by that service. */
private fun checkKeys(): String {
    val lines = ArrayList<String>()
    if (Config.hasTmdb) lines += "TMDB: " + runCatching {
        Net.get("https://api.themoviedb.org/3/configuration?api_key=${Config.TMDB_KEY}")
        "يعمل"
    }.getOrElse { Net.describe(it) }
    if (Config.hasTrakt) lines += "Trakt: " + runCatching {
        Net.get("https://api.trakt.tv/movies/trending?limit=1",
            mapOf("Content-Type" to "application/json", "trakt-api-version" to "2",
                "trakt-api-key" to Config.TRAKT_CLIENT_ID))
        "يعمل"
    }.getOrElse { Net.describe(it) }
    if (Config.hasGemini) lines += "Gemini: " + runCatching {
        Net.get("https://generativelanguage.googleapis.com/v1beta/models",
            mapOf("x-goog-api-key" to Config.GEMINI_KEY))
        "يعمل"
    }.getOrElse { Net.describe(it) }
    return lines.ifEmpty { listOf("لا توجد مفاتيح لفحصها.") }.joinToString("\n")
}
