package com.saridub.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun metaLine(t: Title): String {
    val kind = if (t.kind == "MOVIE") "فيلم" else "مسلسل"
    val year = t.year?.toString() ?: "—"
    return "$year  •  $kind  •  ${t.source}"
}

@Composable
private fun LinearLoading() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Violet, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("جاري البحث في المصادر…", color = TextMid, fontSize = 13.sp)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var tmdb by remember { mutableStateOf("") }
    var trakt by remember { mutableStateOf("") }
    var gemini by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var testReport by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun keyStatus(): String =
        "TMDB: ${if (Config.hasTmdb) "مضبوط" else "ناقص"}  •  " +
        "Trakt: ${if (Config.hasTrakt) "مضبوط" else "ناقص"}  •  " +
        "Gemini: ${if (Config.hasGemini) "مضبوط" else "ناقص"}"

    fun saveEnteredKeys(): Boolean = Config.saveApiKeys(tmdb, trakt, gemini)

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())) {
        TopBar("الإعدادات", onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("مفاتيح الخدمات", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "أدخل مفاتيحك هنا مرة واحدة. تُحفظ في مساحة التطبيق الخاصة على هذا الهاتف، ولا تحتاج إلى تعديل local.properties أو إعادة بناء APK. الحقول الفارغة تحتفظ بالمفاتيح المحفوظة سابقاً.",
                color = TextMid, fontSize = 13.sp
            )
            OutlinedTextField(
                value = tmdb, onValueChange = { tmdb = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("TMDB API key / Read token") },
                placeholder = { Text(if (Config.hasTmdb) "مفتاح محفوظ — اتركه فارغاً للاحتفاظ به" else "أدخل مفتاح TMDB") },
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = trakt, onValueChange = { trakt = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Trakt Client ID") },
                placeholder = { Text(if (Config.hasTrakt) "مفتاح محفوظ — اتركه فارغاً للاحتفاظ به" else "أدخل Trakt Client ID") },
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = gemini, onValueChange = { gemini = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Gemini API key") },
                placeholder = { Text(if (Config.hasGemini) "مفتاح محفوظ — اتركه فارغاً للاحتفاظ به" else "أدخل GEMINI_KEY للترجمة والدبلجة") },
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (saveEnteredKeys()) {
                        tmdb = ""; trakt = ""; gemini = ""
                        notice = "تم حفظ المفاتيح في هذا الجهاز."
                        testReport = ""
                    } else {
                        notice = "تعذّر حفظ المفاتيح. أعد فتح التطبيق وحاول مجدداً."
                    }
                }
            ) { Text("حفظ المفاتيح") }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !checking,
                onClick = {
                    if (saveEnteredKeys()) {
                        tmdb = ""; trakt = ""; gemini = ""
                        notice = "جاري فحص المفاتيح بطلبات حقيقية…"
                        testReport = ""
                        checking = true
                        scope.launch {
                            val report = withContext(Dispatchers.IO) {
                                val lines = mutableListOf<String>()
                                if (Config.hasTmdb) {
                                    try {
                                        val key = Config.TMDB_KEY
                                        val bearer = key.startsWith("eyJ") && key.count { it == '.' } >= 2
                                        val endpoint = if (bearer) {
                                            "https://api.themoviedb.org/3/configuration"
                                        } else {
                                            "https://api.themoviedb.org/3/configuration?api_key=${Net.enc(key)}"
                                        }
                                        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
                                        Net.get(endpoint, headers, timeout = 9000)
                                        lines += "TMDB: صالح ويستجيب"
                                    } catch (e: Exception) {
                                        lines += "TMDB: ${Net.describe(e)}"
                                    }
                                } else lines += "TMDB: لم يُضف مفتاح بعد"

                                if (Config.hasTrakt) {
                                    try {
                                        Net.get(
                                            "https://api.trakt.tv/search/movie?query=matrix&limit=1",
                                            mapOf("Content-Type" to "application/json", "trakt-api-version" to "2", "trakt-api-key" to Config.TRAKT_CLIENT_ID),
                                            timeout = 9000
                                        )
                                        lines += "Trakt: صالح ويستجيب"
                                    } catch (e: Exception) {
                                        lines += "Trakt: ${Net.describe(e)}"
                                    }
                                } else lines += "Trakt: لم يُضف Client ID بعد"

                                if (Config.hasGemini) {
                                    try {
                                        Gemini.generate(JSONArray().put(JSONObject().put("text", "Reply with OK only.")), json = false)
                                        lines += "Gemini: صالح ويستجيب"
                                    } catch (e: Exception) {
                                        lines += "Gemini: ${Net.describe(e)}"
                                    }
                                } else lines += "Gemini: لم يُضف مفتاح بعد"
                                lines.joinToString("\n")
                            }
                            testReport = report
                            notice = "انتهى فحص مفاتيح API."
                            checking = false
                        }
                    } else {
                        notice = "تعذّر حفظ المفاتيح."
                    }
                }
            ) { Text(if (checking) "جاري الفحص…" else "حفظ وفحص المفاتيح") }
            if (notice.isNotBlank()) Text(notice, color = if (checking) TextMid else Violet, fontSize = 13.sp)
            Text(keyStatus(), color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (testReport.isNotBlank()) Text(testReport, color = TextMid, fontSize = 13.sp)

            InfoCard("البحث", "TMDB وTrakt وTVMaze وCinemeta تُستعلم بالتوازي. TVMaze وCinemeta لا يحتاجان مفتاحاً، لذا يظل البحث متاحاً عند عدم ضبط مفاتيح TMDB/Trakt.")
            InfoCard("الترجمة والدبلجة", "Gemini (${Config.GEMINI_MODEL}) يُستخدم لترجمة المانجا وتحليل الحوار. يلزم مفتاح Gemini صالح واتصال إنترنت.")
            InfoCard("التشغيل", "أُزيل مصدر Internet Archive كما طلبت. TMDB وTrakt يعرضان بيانات العناوين فقط وليسا مستضيفين للفيديو. يمكنك تشغيل فيديو من جهازك وترجمته من زر +.")
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp)) {
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = TextMid, fontSize = 13.sp)
    }
}
