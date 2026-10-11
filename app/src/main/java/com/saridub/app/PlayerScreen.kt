package com.saridub.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerScreen(title: Title, items: List<Playable>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val main = remember { ExoPlayer.Builder(ctx).build() }
    val dub = remember { ExoPlayer.Builder(ctx).build() }

    var idx by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("جاري تجهيز المصدر…") }
    var srcUrl by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableStateOf(0L) }
    val srtLines = remember { mutableStateListOf<Line>() }
    val aiLines = remember { mutableStateListOf<Line>() }
    val dubQueue = remember { mutableStateListOf<DubCue>() }
    val played = remember { HashSet<Long>() }
    val doneSegs = remember { HashSet<Int>() }

    DisposableEffect(main) {
        // Failover: a broken source moves to the next one.
        val mainListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { idx += 1 }
        }
        // Restore the original volume once a dubbed line finishes.
        val dubListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) main.volume = 1f
            }
        }
        main.addListener(mainListener)
        dub.addListener(dubListener)
        onDispose {
            main.removeListener(mainListener)
            dub.removeListener(dubListener)
            main.release()
            dub.release()
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
        srtLines.clear(); aiLines.clear(); dubQueue.clear(); played.clear(); doneSegs.clear()
        srcUrl = src.url
        main.setMediaItem(MediaItem.fromUri(src.url))
        main.prepare()
        main.playWhenReady = true
        if (src.subtitleUrl != null) {
            srtLines.addAll(runCatching { SubtitlePipeline.arabicLines(ctx, src.subtitleUrl) }.getOrDefault(emptyList()))
        }
    }

    // Playback loop: picks up segments ahead of the playhead, plays dub lines, and tracks position.
    LaunchedEffect(srcUrl) {
        val url = srcUrl ?: return@LaunchedEffect
        var lastPos = 0L
        var busy = false
        while (true) {
            delay(200)
            val p = main.currentPosition
            pos = p
            if (abs(p - lastPos) > 3000) played.clear()   // user seeked
            lastPos = p
            if (!main.isPlaying) continue

            if (AppSettings.dubEnabled && !busy) {
                val seg = (p / DubEngine.SEG_MS).toInt()
                val target = listOf(seg, seg + 1).firstOrNull { it !in doneSegs }
                if (target != null) {
                    busy = true
                    doneSegs.add(target)
                    launch {
                        try {
                            status = "جاري ترجمة المقطع ${target + 1}…"
                            val cues = DubEngine.segment(ctx, url, target)
                            dubQueue.addAll(cues)
                            if (cues.any { it.file == null }) status = "تمت الترجمة النصية، لكن تعذّرت دبلجة بعض الجمل صوتياً."
                            if (srtLines.isEmpty()) aiLines.addAll(cues.map { Line(it.startMs, it.endMs, it.text) })
                        } catch (e: Exception) {
                            status = "تعذّرت ترجمة المقطع ${target + 1}: ${Net.describe(e)}"
                            // A failed segment is skipped; the video keeps playing with its original audio.
                        } finally {
                            busy = false
                        }
                    }
                }
            }

            val due = dubQueue.firstOrNull { it.startMs !in played && p >= it.startMs && p < it.endMs + 1500 }
            if (due != null) {
                played.add(due.startMs)
                val voiceFile = due.file
                if (voiceFile != null && voiceFile.isFile && voiceFile.length() > 0L) {
                    dub.setMediaItem(MediaItem.fromUri(Uri.fromFile(voiceFile)))
                    dub.prepare()
                    dub.play()
                    main.volume = 0.2f
                }
            }
        }
    }

    val subs = if (srtLines.isNotEmpty()) srtLines else aiLines
    val currentLine = subs.lastOrNull { pos >= it.startMs && pos <= it.endMs + 300 }?.text ?: ""

    Column(Modifier.fillMaxSize().background(Color(0xFF0D0E13))) {
        TopBar(title.title, onBack)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AndroidView(
                factory = { c -> PlayerView(c).also { it.player = main; it.useController = true } },
                modifier = Modifier.fillMaxSize()
            )
            if (currentLine.isNotEmpty()) {
                Text(
                    currentLine, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (AppSettings.dubEnabled) "$status  •  الدبلجة مفعّلة" else "$status  •  الدبلجة متوقفة",
            color = Color(0xFF9AA0B4), fontSize = 14.sp, modifier = Modifier.padding(16.dp)
        )
    }
}
