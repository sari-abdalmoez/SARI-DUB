
package com.saridub.app

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(initialQuery: String, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    var input by remember { mutableStateOf(initialQuery) }
    var active by remember { mutableStateOf(initialQuery.trim()) }
    var tab by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<SearchEngine.Scored>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            input = spoken
            active = spoken.trim()
        }
    }

    LaunchedEffect(input) {
        delay(350)
        active = input.trim()
    }

    LaunchedEffect(active, retry) {
        if (active.isBlank()) {
            results = emptyList()
            loading = false
            status = ""
            return@LaunchedEffect
        }
        loading = true
        status = ""
        try {
            results = SearchEngine.search(active) { partial -> results = partial }
            status = SearchEngine.statusLine()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            status = Net.describe(e)
        } finally {
            loading = false
        }
    }

    val unsupportedCategory = tab >= 2
    val shown = if (unsupportedCategory) emptyList() else results.filter { scored ->
        when (tab) {
            1 -> scored.title.kind == "MOVIE" || scored.title.kind == "SERIES"
            else -> true
        }
    }
    val categoryMessage = when (tab) {
        2 -> "FightZone غير مربوط بمزوّد بيانات في هذه النسخة؛ لن أعرض نتائج وهمية."
        3 -> "مصدر الرياضة غير مربوط حالياً؛ نتائج الأفلام والمسلسلات تعمل بشكل منفصل."
        else -> ""
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            // Dark header and compact search box modeled on the supplied HTML.
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF121318))
                    .border(1.dp, Color.White.copy(alpha = 0.045f))
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconCircle(Icons.Filled.ArrowBack, "Back", onBack)
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1E2029))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (input.isNotEmpty()) {
                            Text(
                                "✕", color = TextMid, fontSize = 15.sp,
                                modifier = Modifier.clickable { input = ""; active = "" }.padding(end = 5.dp)
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Start),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { active = input.trim() }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) Text("بحث عن فيلم أو مسلسل…", color = Color(0xFF8F93A3), fontSize = 14.sp)
                                    inner()
                                }
                            }
                        )
                        Text("⌕", color = Color(0xFF9AA0B4), fontSize = 20.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable {
                            runCatching { voice.launch(voiceIntent()) }
                                .onFailure { status = "لا يوجد محرك تعرّف على الصوت على هذا الجهاز" }
                        },
                        contentAlignment = Alignment.Center
                    ) { Text("🎙", fontSize = 18.sp) }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    listOf("الجميع", "أفلام/مسلسلات", "FightZone", "رياضة").forEachIndexed { index, label ->
                        Column(Modifier.clickable { tab = index }.padding(top = 4.dp, bottom = 0.dp)) {
                            Text(
                                label,
                                color = if (tab == index) Color.White else Color(0xFF8D91A0),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                            if (tab == index) {
                                Spacer(Modifier.height(7.dp))
                                Box(Modifier.width(24.dp).height(2.dp).background(Color.White))
                            } else Spacer(Modifier.height(9.dp))
                        }
                    }
                }
            }

            if (loading) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Sky, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جاري البحث في TMDB وTrakt وTVMaze والمصادر الاحتياطية…", color = TextMid, fontSize = 12.sp)
                }
            }
            if (status.isNotBlank()) {
                Text(
                    "حالة المصادر: $status", color = Color(0xFFFFB86B), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when {
                categoryMessage.isNotBlank() -> Text(
                    categoryMessage, color = TextMid, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(20.dp)
                )
                active.isBlank() && !loading -> Text(
                    "اكتب اسم فيلم أو مسلسل بالعربي أو الإنجليزي", color = TextMid, fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
                !loading && shown.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("لا توجد نتائج لـ ‘$active’ من المصادر المتاحة حالياً.", color = TextMid, fontSize = 13.sp)
                    GradientButton("إعادة المحاولة") { retry++ }
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shown, key = { it.title.key }) { scored ->
                    val item = scored.title
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF16181F))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .clickable { onOpen(item) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Poster(item.poster, Modifier.size(64.dp, 80.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title, color = Color(0xFFF3F4F6), fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(item.source, color = Sky, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${item.year ?: "—"}  •  ${if (item.kind == "MOVIE") "فيلم" else "مسلسل"}",
                                    color = Color(0xFF9296A5), fontSize = 11.sp)
                                if (item.rating > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC233), modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(String.format("%.1f", item.rating), color = Color(0xFFFFC233), fontSize = 11.sp)
                                }
                            }
                            if (item.overview.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(item.overview, color = Color(0xFF8F93A3), fontSize = 10.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun voiceIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "قل اسم الفيلم أو المسلسل")
