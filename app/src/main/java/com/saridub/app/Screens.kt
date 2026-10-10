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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun metaLine(t: Title): String {
    val kind = if (t.kind == "MOVIE") "فيلم" else "مسلسل"
    val year = t.year?.toString() ?: "—"
    return "$year  •  $kind  •  ${t.source}"
}

@Composable
fun HomeScreen(
    onSearch: (String) -> Unit,
    onOpen: (Title) -> Unit,
    onManga: () -> Unit,
    onSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var trending by remember { mutableStateOf<List<Title>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<Title>>(emptyList()) }

    LaunchedEffect(Unit) { trending = Trending.load() }
    LaunchedEffect(query) { suggestions = SearchEngine.suggest(query) }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SARI DUB", color = Accent, fontWeight = FontWeight.Black, fontSize = 26.sp)
            IconCircle(Icons.Filled.Settings, "Settings", onSettings)
        }
        Spacer(Modifier.height(18.dp))
        SearchBar(query, { query = it }, onSubmit = { if (query.isNotBlank()) onSearch(query.trim()) })

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions, key = { it.key }) { t ->
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Card)
                            .clickable { onOpen(t) }.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(t.title, color = TextMain, fontSize = 13.sp, maxLines = 1) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GradientButton("ترجمة المانجا", onClick = onManga)
        Spacer(Modifier.height(18.dp))
        Text("الرائج هذا الأسبوع", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(trending, key = { it.key }) { t ->
                Column(Modifier.clickable { onOpen(t) }) {
                    Poster(t.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Spacer(Modifier.height(4.dp))
                    Text(t.title, color = TextMain, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SearchScreen(query: String, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    var results by remember { mutableStateOf<List<SearchEngine.Scored>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        loading = true
        results = SearchEngine.search(query) { partial -> results = partial }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("نتائج: $query", onBack)
        if (loading) LinearLoading()
        if (!loading && results.isEmpty()) {
            Text("لا توجد نتائج. جرّب كتابة الاسم بشكل آخر أو أضف سنة الإصدار.", color = TextMid,
                modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(results, key = { it.title.key }) { s ->
                val t = s.title
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                        .clickable { onOpen(t) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Poster(t.poster, Modifier.size(64.dp, 92.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(metaLine(t), color = TextMid, fontSize = 12.sp)
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

@Composable
private fun LinearLoading() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Violet, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("جاري البحث في المصادر…", color = TextMid, fontSize = 13.sp)
    }
}

@Composable
fun DetailsScreen(title: Title, onWatch: (List<Playable>) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(title.title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Poster(title.poster, Modifier.size(200.dp, 300.dp))
            Spacer(Modifier.height(16.dp))
            Text(title.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(6.dp))
            Text(metaLine(title), color = TextMid, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            if (title.overview.isNotBlank()) {
                Text(title.overview, color = TextMid, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
            }
            GradientButton(if (busy) "جاري البحث عن مصدر…" else "مشاهدة", enabled = !busy) {
                scope.launch {
                    busy = true
                    val items = Playback.resolve(title)
                    busy = false
                    onWatch(items)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MangaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<Pair<Bitmap, Bitmap?>>>(emptyList()) }
    var progress by remember { mutableStateOf("") }
    var showOriginal by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            val list = mutableListOf<Pair<Bitmap, Bitmap?>>()
            uris.forEachIndexed { i, uri ->
                progress = "ترجمة الصفحة ${i + 1} من ${uris.size}…"
                val src = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) }
                val translated = runCatching { MangaTranslator.translatePage(src, "Arabic") }.getOrNull()
                list.add(src to translated)
                pages = list.toList()
            }
            progress = ""
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("ترجمة المانجا", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GradientButton("اختر صفحات") { picker.launch("image/*") } }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(26.dp)).background(Card).clickable { showOriginal = !showOriginal }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) { Text(if (showOriginal) "الترجمة" else "الأصل", color = TextMain, fontSize = 14.sp) }
        }
        if (progress.isNotEmpty()) Text(progress, color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        if (pages.isEmpty() && progress.isEmpty()) {
            Text("اختر صفحة أو أكثر من المانجا. ستُحذف الكلمات الأصلية داخل الفقاعات وتُكتب الترجمة مكانها.",
                color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages) { (original, translated) ->
                val shown = if (showOriginal || translated == null) original else translated
                Image(shown.asImageBitmap(), null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                if (translated == null) Text("تعذرت ترجمة هذه الصفحة، تحقق من الاتصال ومفتاح Gemini.",
                    color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("الإعدادات", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoCard("البحث", "TMDB، TVMaze، Trakt تعمل بالتوازي مع مهلة زمنية لكل مزود. إذا فشل أحدها تعمل البقية.")
            InfoCard("الترجمة", "Gemini (${Config.GEMINI_MODEL}) لترجمة الفقاعات وملفات SRT، مع تخزين الترجمة مؤقتاً.")
            InfoCard("التشغيل", "مصادر مرخّصة أو في الملك العام فقط (Internet Archive). لا تُنشأ روابط وهمية.")
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
