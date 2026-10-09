package com.saridub.app

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@Composable
fun PlayerScreen(r: Route.Player, nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SariApp
    val project = remember { r.id?.let { runCatching { app.store.load(it) }.getOrNull() } }
    val video = remember { ExoPlayer.Builder(ctx).build() }
    val dub = remember { ExoPlayer.Builder(ctx).build() }
    var dubOn by remember { mutableStateOf(project?.hasDub == true) }
    var volume by remember { mutableStateOf(1f) }
    var ready by remember { mutableStateOf(listOf<Int>()) }
    var bufferMs by remember { mutableStateOf(0L) }
    var fullscreen by remember { mutableStateOf(false) }
    val activity = ctx.findActivity()

    DisposableEffect(Unit) {
        onDispose {
            video.release(); dub.release()
            ProcessingState.playbackMs.value = 0L
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    LaunchedEffect(Unit) {
        val uri = project?.sourceUri ?: r.url ?: return@LaunchedEffect
        val b = MediaItem.Builder().setUri(Uri.parse(uri))
        if (project != null && project.segments.any { it.translated.isNotBlank() }) {
            val f = File(ctx.cacheDir, "sub_${project.id}.srt")
            withContext(Dispatchers.IO) { f.writeText(Exporter.srt(project, true)) }
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(project.dstLang).setLabel("SARI translated").build()))
        }
        video.setMediaItem(b.build()); video.prepare()
    }

    // Poll which dub chunks are finished (processing runs in the foreground service).
    LaunchedEffect(project) {
        if (project == null) return@LaunchedEffect
        while (true) {
            val fresh = withContext(Dispatchers.IO) { runCatching { app.store.load(project.id) }.getOrNull() }
            val list = fresh?.ready?.toList() ?: emptyList()
            if (list != ready) {
                ready = list
                val dir = File(app.store.dir(project.id), "chunks")
                dub.setMediaItems(list.map { MediaItem.fromUri(Uri.fromFile(File(dir, "chunk_$it.wav"))) }, false)
                dub.prepare()
            }
            delay(2500)
        }
    }

    // Sync loop: video is the master clock; the dub chunk matching the position follows it.
    LaunchedEffect(project, dubOn, ready) {
        while (true) {
            val pos = video.currentPosition
            ProcessingState.playbackMs.value = pos
            if (project != null) {
                val c = (pos / project.chunkMs).toInt()
                val li = ready.indexOf(c)
                if (dubOn && li >= 0) {
                    video.volume = 0f; dub.volume = volume
                    if (dub.playbackParameters != video.playbackParameters) dub.playbackParameters = PlaybackParameters(video.playbackParameters.speed)
                    val off = pos - c * project.chunkMs
                    if (dub.currentMediaItemIndex != li || abs(dub.currentPosition - off) > 250) dub.seekTo(li, off)
                    dub.playWhenReady = video.isPlaying
                } else { dub.playWhenReady = false; video.volume = volume }
                var k = c; while (k in ready) k++
                bufferMs = if (dubOn) maxOf(0L, k * project.chunkMs - pos) else 0L
            } else video.volume = volume
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) TopBar(r.title, nav)
        AndroidView(
            factory = { PlayerView(it).apply { player = video; useController = true } },
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16 / 9f)
        )
        if (!fullscreen) Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SCard {
                if (project != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dubbing", Modifier.weight(1f)); Switch(dubOn, { dubOn = it })
                    }
                    Text("Dub Buffer: ${fmtTime(bufferMs)}" + if (ready.size < project.chunkCount) "  (processing…)" else "  (complete)", color = Accent2, fontSize = 14.sp)
                    if (ready.isEmpty()) Text("No dubbed chunks yet — original audio plays.", color = Dim, fontSize = 12.sp)
                } else Text("Streaming a source URL. Create a project from Search → Dub to dub it.", color = Dim, fontSize = 12.sp)
                Text("Volume", color = Dim, fontSize = 12.sp)
                Slider(volume, { volume = it }, valueRange = 0f..1f)
                Text("Use the ⚙ button in the player for speed, audio track and subtitle selection.", color = Dim, fontSize = 11.sp)
            }
            GradientButton("Fullscreen", Modifier.fillMaxWidth()) {
                fullscreen = true
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }
    androidx.activity.compose.BackHandler(fullscreen) {
        fullscreen = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }
}
