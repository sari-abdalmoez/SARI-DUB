package com.saridub.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MangaPage(val name: String, val original: Bitmap?, val translated: Bitmap?, val error: String?)

/** Saves a PNG to Pictures/SariDub through MediaStore (no storage permission needed on Android 10+). */
fun saveBitmapToPictures(ctx: Context, bmp: Bitmap, name: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SariDub")
        }
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
    } catch (e: Exception) {
        ctx.contentResolver.delete(uri, null, null)
        false
    }
}

@Composable
fun MangaScreen(initial: List<Uri>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<MangaPage>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun process(uris: List<Uri>) {
        job?.cancel()
        pages.clear()
        notice = ""
        job = scope.launch {
            uris.forEachIndexed { i, uri ->
                progress = "ترجمة الصفحة ${i + 1} من ${uris.size}…"
                val name = "page_${i + 1}"
                try {
                    val src = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) }
                    val tr = MangaTranslator.translatePage(src, "Arabic")
                    pages.add(MangaPage(name, src, tr, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pages.add(MangaPage(name, null, null, Net.describe(e)))
                }
            }
            progress = ""
            notice = "اكتملت المعالجة"
        }
    }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    LaunchedEffect(initial) {
        if (initial.isNotEmpty()) process(initial)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) process(uris)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("ترجمة المانجا", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GradientButton("اختر صفحات") { picker.launch("image/*") } }
            if (job?.isActive == true) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(26.dp)).background(Card)
                        .clickable { job?.cancel(); progress = "" }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) { Text("إلغاء", color = TextMain, fontSize = 14.sp) }
            }
        }
        if (progress.isNotEmpty()) Text(progress, color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        if (notice.isNotEmpty()) Text(notice, color = Violet, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        if (pages.isEmpty() && progress.isEmpty()) {
            Text(
                "اختر صفحة أو أكثر. تُمحى الكلمات الأصلية داخل الفقاعات وتُكتب الترجمة مكانها. " +
                        "تحتاج الترجمة إلى مفتاح Gemini صالح في إعدادات التطبيق أو في مفاتيح نسخة البناء.",
                color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(pages) { page ->
                val shown = page.translated ?: page.original
                if (shown != null) {
                    Image(
                        shown.asImageBitmap(), null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    )
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (page.translated != null) "مترجمة" else "لم تُترجم",
                            color = TextMid, fontSize = 12.sp, modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp)).background(Card)
                                .clickable {
                                    val saved = saveBitmapToPictures(ctx, shown, "sari_${page.name}.png")
                                    notice = if (saved) "حُفظت الصفحة في Pictures/SariDub" else "تعذّر حفظ الصفحة"
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text("حفظ", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    Text("${page.name}: ${page.error ?: "فشلت المعالجة"}", color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            }
        }
    }
}
