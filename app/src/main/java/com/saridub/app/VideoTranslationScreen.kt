package com.saridub.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val VideoLightBg = Color(0xFFF0F2F5)
private val VideoShadow = Color(0xFFD1D9E6)
private val VideoOrange = Color(0xFFFF6B00)
private val VideoInk = Color(0xFF2D3748)

@Composable
fun VideoTranslationScreen(uri: Uri, onBack: () -> Unit, onTranslate: (String) -> Unit) {
    val ctx = LocalContext.current
    var language by remember(uri) { mutableStateOf(AppSettings.targetLanguage) }
    val name = remember(uri) {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "Selected video"
    }
    val options = listOf("Arabic" to "العربية", "English" to "English", "French" to "Français", "Spanish" to "Español")

    Column(
        Modifier.fillMaxSize().background(VideoLightBg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).shadow(6.dp, CircleShape, ambientColor = VideoShadow, spotColor = Color.White)
                    .clip(CircleShape).background(VideoLightBg).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, "Back", tint = VideoInk) }
            Spacer(Modifier.weight(1f))
            Text("SARI DUB", color = VideoOrange, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(46.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Video", color = VideoInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().shadow(9.dp, RoundedCornerShape(24.dp), ambientColor = VideoShadow, spotColor = Color.White)
                .clip(RoundedCornerShape(24.dp)).background(VideoLightBg).padding(14.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1E2B)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", color = VideoOrange, fontSize = 42.sp)
                    Text("Selected video", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(name, color = VideoInk, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("سيُعالج الحوار على مقاطع أثناء التشغيل لتقليل استهلاك الذاكرة.", color = Color(0xFF6B7385), fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("Target language", color = VideoInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center) { options.take(2).forEach { (code, label) -> LanguageOption(code, label, language) { language = code; AppSettings.targetLanguage = code } } }
            Row(horizontalArrangement = Arrangement.Center) { options.drop(2).forEach { (code, label) -> LanguageOption(code, label, language) { language = code; AppSettings.targetLanguage = code } } }
        }
        Spacer(Modifier.height(12.dp))
        if (!Config.hasGemini) {
            Text("مفتاح Gemini غير مضبوط. افتح الإعدادات وأدخل GEMINI_KEY ثم ارجع إلى هنا؛ لا يلزم إعادة بناء التطبيق.",
                color = Color(0xFF9B3B20), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(54.dp).shadow(8.dp, RoundedCornerShape(22.dp), ambientColor = VideoShadow, spotColor = Color.White)
                .clip(RoundedCornerShape(22.dp)).background(VideoLightBg).clickable {
                    AppSettings.targetLanguage = language
                    onTranslate(language)
                },
            contentAlignment = Alignment.Center
        ) { Text("ترجمة وتشغيل", color = VideoOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        Text("تحتاج ترجمة الصوت إلى اتصال إنترنت ومفتاح Gemini صالحًا. يمكنك حفظ المفتاح وفحصه من الإعدادات.",
            color = Color(0xFF6B7385), fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LanguageOption(code: String, label: String, selectedLanguage: String, onSelect: () -> Unit) {
    val selected = selectedLanguage == code
    Box(
        Modifier.padding(4.dp).shadow(if (selected) 2.dp else 5.dp, RoundedCornerShape(18.dp), ambientColor = VideoShadow, spotColor = Color.White)
            .clip(RoundedCornerShape(18.dp)).background(if (selected) Color(0xFFFFE6D5) else VideoLightBg)
            .clickable(onClick = onSelect).padding(horizontal = 14.dp, vertical = 10.dp)
    ) { Text(label, color = if (selected) VideoOrange else VideoInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
