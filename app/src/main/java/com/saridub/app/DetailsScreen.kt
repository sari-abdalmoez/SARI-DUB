package com.saridub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    var related by remember { mutableStateOf<List<Title>>(emptyList()) }
    var relatedError by remember { mutableStateOf("") }
    val tabs = listOf("نظرة عامة", "المصادر", "الترجمة", "الإعداد")

    LaunchedEffect(title.key) {
        runCatching { Recommendations.load(title) }
            .onSuccess { related = it }
            .onFailure { relatedError = Net.describe(it) }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(title.title, onBack)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { i, name ->
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (i == tab) Violet else Card)
                        .clickable { tab = i }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) { Text(name, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(14.dp))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when (tab) {
                0 -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Poster(title.poster, Modifier.size(120.dp, 180.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${title.year ?: "—"}  •  ${if (title.kind == "MOVIE") "فيلم" else "مسلسل"}  •  ${title.source}",
                                color = TextMid, fontSize = 13.sp
                            )
                            if (title.rating > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text("★ ${String.format("%.1f", title.rating)}", color = Accent, fontSize = 14.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (title.overview.isNotBlank()) {
                        Text(title.overview, color = TextMid, fontSize = 14.sp)
                    } else {
                        Text("لا يوجد وصف متاح من مزود البيانات.", color = TextMid, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    GradientButton(if (busy) "جاري البحث عن مصدر…" else "مشاهدة", enabled = !busy) {
                        scope.launch {
                            busy = true
                            val items = Playback.resolve(title)
                            sources = items
                            busy = false
                            onWatch(items)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("مقترحات", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    if (related.isEmpty()) {
                        Text(
                            if (relatedError.isNotBlank()) "تعذّر تحميل المقترحات: $relatedError"
                            else "لا توجد مقترحات حالياً لهذا العنوان.",
                            color = TextMid, fontSize = 13.sp
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(related, key = { it.key }) { t ->
                                Column(Modifier.width(100.dp).clickable { onOpenRelated(t) }) {
                                    Poster(t.poster, Modifier.size(100.dp, 150.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(t.title, color = TextMain, fontSize = 12.sp, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                1 -> {
                    GradientButton(if (busy) "جاري البحث…" else "البحث عن مصادر تشغيل", enabled = !busy) {
                        scope.launch {
                            busy = true
                            sources = Playback.resolve(title)
                            busy = false
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val list = sources
                    if (list == null) {
                        Text("اضغط للبحث عن مصادر تشغيل مرخّصة (الملك العام فقط).", color = TextMid, fontSize = 13.sp)
                    } else if (list.isEmpty()) {
                        Text(
                            "لا يوجد مصدر متاح لهذا العنوان حالياً من المصادر المرخّصة. لا يُعرض أي رابط وهمي.",
                            color = Color(0xFFFFB86B), fontSize = 13.sp
                        )
                    } else {
                        list.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp)).background(Card)
                                    .clickable { onWatch(listOf(p) + list.filter { it != p }) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, color = TextMain, fontSize = 14.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(p.sourceName, color = TextMid, fontSize = 12.sp)
                                }
                                Text("تشغيل", color = Violet, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                2 -> {
                    InfoBlock(
                        "الترجمة",
                        "إذا وُجد ملف SRT للمصدر يُترجم إلى العربية ويُعرض مع الفيديو. " +
                                "إذا لم يوجد، تُفرَّغ الحوارات وتُترجم تلقائياً من صوت كل مقطع."
                    )
                    Spacer(Modifier.height(12.dp))
                    val table = Casting.table()
                    InfoBlock(
                        "الأصوات",
                        if (table.isEmpty()) "تُعيَّن أصوات لكل شخصية عند بدء الدبلجة، وتحتفظ كل شخصية بصوتها طوال الجلسة."
                        else table.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    )
                }

                3 -> {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("الدبلجة الصوتية", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("تشغيل الحوار المترجم بصوت الشخصية ونبرتها", color = TextMid, fontSize = 13.sp)
                            }
                            Switch(
                                checked = AppSettings.dubEnabled,
                                onCheckedChange = { AppSettings.dubEnabled = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = Violet)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    InfoBlock(
                        "مفتاح Gemini",
                        if (Config.hasGemini) "مفتاح Gemini مضبوط (لا تظهر قيمته في التطبيق)."
                        else "غير مضبوط. أضف GEMINI_KEY إلى GitHub Secrets وأعد بناء التطبيق، أو أدخل المفتاح في الإعدادات."
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp)) {
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = TextMid, fontSize = 13.sp)
    }
}
