package com.saridub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(title: Title, onWatch: (List<Playable>) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val tabs = listOf("نظرة عامة", "الترجمة", "الأصوات", "الإعداد")

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

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
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
                        Spacer(Modifier.height(18.dp))
                    }
                    GradientButton(if (busy) "جاري البحث عن مصدر…" else "مشاهدة", enabled = !busy) {
                        scope.launch {
                            busy = true
                            val items = Playback.resolve(title)
                            busy = false
                            onWatch(items)
                        }
                    }
                }
                1 -> InfoBlock(
                    "الترجمة",
                    "إذا كان للمصدر ملف SRT يُترجم إلى العربية ويُعرض مع الفيديو. " +
                            "إذا لم يوجد، تُفرَّغ الحوارات وتُترجم تلقائياً من الصوت الأصلي في كل مقطع."
                )
                2 -> {
                    val table = Casting.table()
                    InfoBlock(
                        "الأصوات",
                        if (table.isEmpty()) "تُعيَّن أصوات لكل شخصية تلقائياً عند بدء الدبلجة. كل شخصية تحتفظ بصوتها طوال الجلسة."
                        else table.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    )
                }
                3 -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp)) {
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
