
package com.saridub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(
    title: Title,
    onWatch: (List<Playable>) -> Unit,
    onOpenRelated: (Title) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Playable>?>(null) }
    var sourceError by remember { mutableStateOf("") }
    var related by remember { mutableStateOf<List<Title>>(emptyList()) }
    var relatedError by remember { mutableStateOf("") }

    fun resolveSources(openWhenFound: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            sourceError = ""
            try {
                val found = Playback.resolve(title)
                sources = found
                if (found.isNotEmpty() && openWhenFound) onWatch(found)
                else if (found.isEmpty()) sourceError =
                    "لم يُعثر على مصدر تشغيل متاح لهذا العنوان. TMDB وTrakt يقدمان معلومات عن العمل ولا يستضيفان ملف الفيديو."
            } catch (e: Exception) {
                sourceError = Net.describe(e)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(title.key) {
        busy = true
        try {
            sources = Playback.resolve(title)
            if (sources.isNullOrEmpty()) sourceError =
                "لم يُعثر على مصدر تشغيل متاح لهذا العنوان. نتائج البحث لا تعني أن الفيديو مستضاف أو قابل للتشغيل."
        } catch (e: Exception) {
            sourceError = Net.describe(e)
        } finally {
            busy = false
        }
        runCatching { Recommendations.load(title) }
            .onSuccess { related = it }
            .onFailure { relatedError = Net.describe(it) }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF121318))) {
        // Cinematic 16:9 poster hero, following the supplied detail-page layout.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            Poster(title.poster, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
            Column(
                Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) { Text("›", color = Color.White, fontSize = 25.sp) }
                    Spacer(Modifier.width(8.dp))
                    Text("SARI DUB  •  معلومات العمل", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        if (busy) "جاري التحقق من مصادر التشغيل…" else "اكتشاف مصادر التشغيل المتاحة",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    if (busy) CircularProgressIndicator(color = Sky, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else GradientButton("مشاهدة") { resolveSources(true) }
                }
                Text(
                    title.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (title.rating > 0) Text("★ ${String.format("%.1f", title.rating)}", color = Color(0xFFFFC233), fontSize = 12.sp)
                    Text("${title.year ?: "—"}  •  ${if (title.kind == "MOVIE") "فيلم" else "مسلسل"}  •  ${title.source}",
                        color = Color(0xFF9296A5), fontSize = 12.sp)
                }
            }

            // Real source list only; never invent 360p/720p options for metadata-only results.
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الموارد المتاحة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("مصادر حقيقية فقط", color = Color(0xFF9296A5), fontSize = 10.sp)
                }
                when {
                    busy && sources == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Sky, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(9.dp))
                        Text("جاري فحص المصادر…", color = TextMid, fontSize = 12.sp)
                    }
                    !sources.isNullOrEmpty() -> sources!!.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2029))
                                .clickable { onWatch(listOf(p) + sources!!.filter { it != p }) }
                                .padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.sourceName, color = Color(0xFF9296A5), fontSize = 11.sp)
                            }
                            Text("تشغيل  ›", color = Sky, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    else -> Text(
                        sourceError.ifBlank { "لا يوجد مصدر تشغيل متاح حالياً لهذا العنوان." },
                        color = Color(0xFFFFB86B), fontSize = 12.sp
                    )
                }
                if (sources.isNullOrEmpty() && !busy) {
                    GradientButton("إعادة البحث عن المصادر") { resolveSources(false) }
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("التفاصيل", "الترجمة", "الإعدادات").forEachIndexed { index, name ->
                    Box(
                        Modifier.clip(RoundedCornerShape(18.dp))
                            .background(if (tab == index) Color(0xFF252A35) else Color(0xFF1E2029))
                            .clickable { tab = index }.padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text(name, color = if (tab == index) Color.White else TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }

            when (tab) {
                0 -> {
                    Text(title.overview.ifBlank { "لا يوجد وصف متاح من مزود البيانات." }, color = TextMid, fontSize = 13.sp)
                    Text("مقترحات لك", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (related.isEmpty()) {
                        Text(relatedError.ifBlank { "لا توجد مقترحات إضافية حالياً." }, color = TextMid, fontSize = 12.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(related, key = { it.key }) { item ->
                                Column(Modifier.width(100.dp).clickable { onOpenRelated(item) }) {
                                    Poster(item.poster, Modifier.size(100.dp, 140.dp))
                                    Spacer(Modifier.height(5.dp))
                                    Text(item.title, color = Color.White, fontSize = 11.sp, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Text("الدبلجة والترجمة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        if (Config.hasGemini) "Gemini متاح في إعدادات البناء. شغّل الدبلجة من الإعدادات أثناء مشاهدة ملف/مصدر له مسار صوت قابل للقراءة."
                        else "مفتاح Gemini غير موجود في هذه النسخة. أضف GEMINI_KEY إلى local.properties أو GitHub Actions Secrets وأعد البناء.",
                        color = TextMid, fontSize = 13.sp
                    )
                    val voiceMap = Casting.table()
                    if (voiceMap.isNotEmpty()) Text(voiceMap.entries.joinToString("\n") { "${it.key}: ${it.value}" }, color = TextMid, fontSize = 12.sp)
                }
                2 -> {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E2029)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("الدبلجة الصوتية", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("ترجمة الحوار وتشغيل الصوت المولّد عند توفر المصدر والمفتاح", color = TextMid, fontSize = 12.sp)
                        }
                        Switch(checked = AppSettings.dubEnabled, onCheckedChange = { AppSettings.dubEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Sky))
                    }
                    Text(
                        "مفاتيح البناء: TMDB ${if (Config.hasTmdb) "موجود" else "مفقود"} • Trakt ${if (Config.hasTrakt) "موجود" else "مفقود"} • Gemini ${if (Config.hasGemini) "موجود" else "مفقود"}",
                        color = TextMid, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
