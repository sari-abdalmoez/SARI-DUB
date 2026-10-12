package com.saridub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        "TMDB: ${if (Config.hasTmdb) "✓" else "✗"}  •  " +
        "Trakt: ${if (Config.hasTrakt) "✓" else "✗"}  •  " +
        "Gemini: ${if (Config.hasGemini) "✓" else "✗"}"

    fun saveEnteredKeys(): Boolean = Config.saveApiKeys(tmdb, trakt, gemini)

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
    ) {
        TopBar("الإعدادات", onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "مفاتيح الخدمات",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Text(
                "أدخل مفاتيحك هنا مرة واحدة. تُحفظ في مساحة التطبيق الخاصة على هذا الهاتف. الحقول الفارغة تحتفظ بالمفاتيح المحفوظة سابقاً.",
                color = TextMid,
                fontSize = 13.sp
            )

            // Status display
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Card, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(keyStatus(), color = TextMid, fontSize = 12.sp)
            }

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = tmdb,
                onValueChange = { tmdb = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("TMDB API key / Read token") },
                placeholder = {
                    Text(
                        if (Config.hasTmdb) "مفتاح محفوظ — اتركه فارغاً للاحتفاظ به"
                        else "أدخل مفتاح TMDB"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMid,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = TextMid
                )
            )

            OutlinedTextField(
                value = trakt,
                onValueChange = { trakt = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Trakt Client ID") },
                placeholder = {
                    Text(
                        if (Config.hasTrakt) "Client ID محفوظ — اتركه فارغاً للاحتفاظ به"
                        else "أدخل Client ID من Trakt"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMid,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = TextMid
                )
            )

            OutlinedTextField(
                value = gemini,
                onValueChange = { gemini = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Google Gemini API key") },
                placeholder = {
                    Text(
                        if (Config.hasGemini) "API key محفوظ — اتركه فارغاً للاحتفاظ به"
                        else "أدخل Gemini API key"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMid,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = TextMid
                )
            )

            Spacer(Modifier.height(4.dp))

            GradientButton(
                text = if (checking) "جاري الفحص..." else "حفظ وفحص المفاتيح",
                enabled = !checking && (tmdb.isNotEmpty() || trakt.isNotEmpty() || gemini.isNotEmpty())
            ) {
                scope.launch {
                    if (!saveEnteredKeys()) {
                        notice = "فشل الحفظ"
                        return@launch
                    }
                    checking = true
                    testReport = "جاري اختبار الخدمات…"

                    try {
                        val results = StringBuilder()
                        
                        // Test TMDB
                        if (Config.hasTmdb) {
                            try {
                                val tmdbUrl =
                                    "https://api.themoviedb.org/3/configuration?api_key=${Config.TMDB_KEY}"
                                Net.get(tmdbUrl, timeout = 5000)
                                results.append("✓ TMDB: تم التحقق بنجاح\n")
                            } catch (e: Exception) {
                                results.append("✗ TMDB: ${e.message}\n")
                            }
                        }

                        // Test Trakt
                        if (Config.hasTrakt) {
                            try {
                                val traktUrl = "https://api.trakt.tv/users/settings"
                                Net.get(
                                    traktUrl, mapOf(
                                        "trakt-api-version" to "2",
                                        "trakt-api-key" to Config.TRAKT_CLIENT_ID
                                    ), timeout = 5000
                                )
                                results.append("✓ Trakt: تم التحقق بنجاح\n")
                            } catch (e: Exception) {
                                results.append("✗ Trakt: ${e.message}\n")
                            }
                        }

                        // Test Gemini
                        if (Config.hasGemini) {
                            try {
                                val geminiUrl =
                                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=${Config.GEMINI_KEY}"
                                val body = """{"contents":[{"parts":[{"text":"test"}]}]}"""
                                val (code, _) = Net.post(geminiUrl, body, mapOf(), timeout = 5000)
                                if (code in 200..299) {
                                    results.append("✓ Gemini: تم التحقق بنجاح\n")
                                } else {
                                    results.append("✗ Gemini: HTTP $code\n")
                                }
                            } catch (e: Exception) {
                                results.append("✗ Gemini: ${e.message}\n")
                            }
                        }

                        testReport = results.toString().ifBlank { "لا توجد مفاتيح للاختبار" }
                        notice = "تم الحفظ بنجاح"
                    } catch (e: Exception) {
                        testReport = "خطأ: ${e.message}"
                        notice = "خطأ في الفحص"
                    } finally {
                        checking = false
                    }
                }
            }

            if (notice.isNotBlank()) {
                Text(notice, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            if (testReport.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Card, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text("نتائج الاختبار:", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(testReport, color = TextMid, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Clear button (less prominent)
            if (Config.hasTmdb || Config.hasTrakt || Config.hasGemini) {
                GradientButton(text = "حذف جميع المفاتيح المحفوظة", enabled = true) {
                    Config.clearSavedApiKeys()
                    notice = "تم حذف المفاتيح"
                    testReport = ""
                    tmdb = ""
                    trakt = ""
                    gemini = ""
                }
            }

            Spacer(Modifier.height(20.dp))

            // Instructions section
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("كيفية الحصول على المفاتيح:", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                Text(
                    "TMDB: تفضل بزيارة themoviedb.org والتسجيل، ثم احصل على API key من إعدادات حسابك.",
                    color = TextMid,
                    fontSize = 10.sp
                )

                Text(
                    "Trakt: سجل دخولك في trakt.tv وأنشئ تطبيق جديد في الإعدادات للحصول على Client ID.",
                    color = TextMid,
                    fontSize = 10.sp
                )

                Text(
                    "Gemini: انتقل إلى cloud.google.com/generative-ai وأنشئ API key مجاني للاستخدام.",
                    color = TextMid,
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
