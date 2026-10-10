package com.saridub.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
fun PlayerScreen(title: Title, items: List<Playable>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var idx by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("جاري تجهيز المصدر…") }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // Failover: a broken source moves on to the next one automatically.
            override fun onPlayerError(error: PlaybackException) {
                idx += 1
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(items, idx) {
        if (items.isEmpty()) {
            status = "لا يوجد مصدر تشغيل مرخّص لهذا العنوان حالياً."
            return@LaunchedEffect
        }
        if (idx >= items.size) {
            status = "تعذّر تشغيل كل المصادر المتاحة."
            return@LaunchedEffect
        }
        val src = items[idx]
        status = "المصدر ${idx + 1} من ${items.size}: ${src.sourceName}"

        val subFile: File? = src.subtitleUrl?.let {
            runCatching { SubtitlePipeline.arabicSubtitle(ctx, it) }.getOrNull()
        }
        val builder = MediaItem.Builder().setUri(src.url)
        if (subFile != null) {
            builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("ar")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
    }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0D0E13))) {
        TopBar(title.title, onBack)
        AndroidView(
            factory = { c -> PlayerView(c).also { it.player = player; it.useController = true } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        Spacer(Modifier.height(12.dp))
        Text(status, color = androidx.compose.ui.graphics.Color(0xFF9AA0B4), fontSize = 14.sp,
            modifier = Modifier.padding(16.dp))
    }
}
