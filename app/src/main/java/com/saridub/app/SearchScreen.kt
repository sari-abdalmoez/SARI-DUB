package com.saridub.app

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(initialQuery: String, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    var input by remember { mutableStateOf(initialQuery) }
    var active by remember { mutableStateOf(initialQuery.trim()) }
    var tab by remember { mutableStateOf(0) }   // 0 all, 1 movies, 2 series
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

    // Search-as-you-type: wait 350 ms after the last keystroke.
    LaunchedEffect(input) {
        delay(350)
        if (input.trim() != active) active = input.trim()
    }

    // Cancelling this effect cancels the in-flight provider requests (a newer query replaces it).
    LaunchedEffect(active, retry) {
        if (active.isBlank()) {
            results = emptyList()
            loading = false
            status = ""
            return@LaunchedEffect
        }
        loading = true
        results = SearchEngine.search(active) { results = it }
        loading = false
        status = SearchEngine.statusLine()
    }

    val shown = results.filter { s ->
        when (tab) {
            1 -> s.title.kind == "MOVIE"
            2 -> s.title.kind == "SERIES"
            else -> true
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconCircle(Icons.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { active = input.trim() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("ابحث بالعربي أو الإنجليزي", color = TextMid, fontSize = 15.sp)
                            inner()
                        }
                    }
                )
                if (input.isNotEmpty()) {
                    Text(
                        "✕", color = TextMid, fontSize = 16.sp,
                        modifier = Modifier.clickable { input = ""; active = "" }.padding(horizontal = 6.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Card)
                    .clickable {
                        runCatching { voice.launch(voiceIntent()) }
                            .onFailure { status = "لا يوجد محرك تعرّف على الصوت على هذا الجهاز" }
                    },
                contentAlignment = Alignment.Center
            ) { Text("🎙", fontSize = 18.sp) }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            listOf("الجميع", "أفلام", "مسلسلات").forEachIndexed { i, name ->
                Column(Modifier.clickable { tab = i }.padding(vertical = 6.dp)) {
                    Text(
                        name,
                        color = if (tab == i) TextMain else TextMid,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (tab == i) Box(Modifier.padding(top = 4.dp).width(28.dp).height(2.dp).background(TextMain))
                }
            }
        }

        if (loading) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Violet, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("جاري البحث في المصادر…", color = TextMid, fontSize = 13.sp)
            }
        }
        if (status.isNotBlank()) {
            Text(
                "بعض المصادر لم تستجب: $status",
                color = Color(0xFFFFB86B), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (active.isBlank()) {
            Text("اكتب اسم فيلم أو مسلسل بالعربي أو الإنجليزي", color = TextMid, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp))
        } else if (!loading && shown.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("لا توجد نتائج لـ \"$active\"", color = TextMid, fontSize = 13.sp)
                GradientButton("إعادة المحاولة") { retry++ }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shown, key = { it.title.key }) { s ->
                val t = s.title
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                        .clickable { onOpen(t) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Poster(t.poster, Modifier.size(64.dp, 92.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${t.year ?: "—"}  •  ${if (t.kind == "MOVIE") "فيلم" else "مسلسل"}  •  ${t.source}",
                            color = TextMid, fontSize = 12.sp
                        )
                        if (t.rating > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC233), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(String.format("%.1f", t.rating), color = Color(0xFFFFC233), fontSize = 12.sp)
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
