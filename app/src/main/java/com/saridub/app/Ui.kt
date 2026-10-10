package com.saridub.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Bg = Color(0xFF0D0E13)
val Card = Color(0xFF1A1C25)
val Accent = Color(0xFFFF7A1A)
val Violet = Color(0xFF7B61FF)
val Sky = Color(0xFF3EA8FF)
val TextMain = Color(0xFFF2F3F7)
val TextMid = Color(0xFF9AA0B4)
val GradientBrush = Brush.horizontalGradient(listOf(Violet, Sky))

@Composable
fun SariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Card, primary = Violet, onPrimary = Color.White,
            onBackground = TextMain, onSurface = TextMain
        ),
        content = content
    )
}

@Composable
fun IconCircle(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = TextMain, modifier = Modifier.size(22.dp)) }
}

@Composable
fun GradientButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (enabled) GradientBrush else Brush.linearGradient(listOf(Card, Card)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

@Composable
fun SearchBar(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = TextMid, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("ابحث بالعربي أو الإنجليزي…", color = TextMid, fontSize = 16.sp)
                    inner()
                }
            }
        )
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconCircle(Icons.Filled.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(12.dp))
        Text(
            title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
    }
}

private val posterCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Poster with memory cache and downscaled decode (budget-phone friendly). */
@Composable
fun Poster(url: String?, modifier: Modifier) {
    var bmp by remember(url) { mutableStateOf(url?.let { posterCache.get(it) }) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) {
            val loaded: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Net.getBytes(url)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                }.getOrNull()
            }
            if (loaded != null) {
                posterCache.put(url, loaded)
                bmp = loaded
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Card), contentAlignment = Alignment.Center) {
        val img: ImageBitmap? = bmp?.asImageBitmap()
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
