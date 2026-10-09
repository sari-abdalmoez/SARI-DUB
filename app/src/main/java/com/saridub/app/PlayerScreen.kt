package com.saridub.app

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.CaptionStyleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.toArgb
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
    // Survive rotation / process recreation.
    var savedPos by rememberSaveable { mutableStateOf(0L) }
    var speed by rememberSaveable { mutableStateOf(1f) }
    var subDelay by rememberSaveable { mutableStateOf(0L) }
    var subSize by rememberSaveable { mutableStateOf(0.0533f) }
    var subBottom by rememberSaveable { mutableStateOf(0.08f) }
    var subBg by rememberSaveable { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    val hasSubs = project != null && project.segments.any { it.translated.isNotBlank() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) { savedPos = video.currentPosition; video.pause(); dub.pause() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(video) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMsg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Network problem while loading the video. Check your connection and retry."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this video (the link may have expired)."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "The video file can no longer be opened. Re-select it."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device cannot decode this video format."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        "The video file is damaged or in an unsupported container."
                    else -> "Playback error: ${error.errorCodeName}"
                }
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) errorMsg = null }
        }
        video.addListener(l)
        onDispose { video.removeListener(l) }
    }

    DisposableEffect(Unit) {
        onDispose {
            video.release(); dub.release()
            ProcessingState.playbackMs.value = 0L
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    // (Re)load the media item. Subtitle delay edits rewrite the cached SRT (no re-translation) and keep position and play state.
    LaunchedEffect(subDelay, retryTick) {
        val uri = project?.sourceUri ?: r.url ?: return@LaunchedEffect
        if (subDelay != 0L) delay(400)   // debounce rapid +/- taps
        val resume = if (video.mediaItemCount > 0) video.currentPosition else savedPos
        val play = video.playWhenReady
        val b = MediaItem.Builder().setUri(Uri.parse(uri))
        if (project != null && hasSubs) {
            val f = File(ctx.cacheDir, "sub_${project.id}.srt")
            withContext(Dispatchers.IO) { f.writeText(SubtitleSync.shiftSrt(Exporter.srt(project, true), subDelay)) }
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(project.dstLang).setLabel("SARI translated")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()))
        }
        errorMsg = null
        video.setMediaItem(b.build(), resume.coerceAtLeast(0L)); video.prepare()
        video.playWhenReady = play
    }
    LaunchedEffect(speed) { video.playbackParameters = PlaybackParameters(speed) }

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
            if (pos > 0) savedPos = pos
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
            update = { pv ->
                pv.subtitleView?.apply {
                    setFractionalTextSize(subSize)
                    setBottomPaddingFraction(subBottom)
                    setStyle(CaptionStyleCompat(
                        androidx.compose.ui.graphics.Color.White.toArgb(),
                        if (subBg) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f).toArgb() else android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        androidx.compose.ui.graphics.Color.Black.toArgb(), null))
                }
            },
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
                Text("Use the ⚙ button in the player for audio track, subtitle track and quality (only tracks that really exist are listed).", color = Dim, fontSize = 11.sp)
            }
            errorMsg?.let { m ->
                SCard {
                    Text(m, color = Bad, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    GradientButton("Retry", Modifier.fillMaxWidth()) { retryTick++ }
                }
            }
            SCard {
                Text("Speed", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                        FilterChip(selected = speed == v, onClick = { speed = v }, label = { Text("${v}x", fontSize = 12.sp) })
                    }
                }
                if (hasSubs) {
                    Spacer(Modifier.height(8.dp))
                    Text("Subtitle delay: ${if (subDelay >= 0) "+" else ""}${subDelay} ms", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ subDelay -= 500 }) { Text("−0.5s") }
                        OutlinedButton({ subDelay -= 100 }) { Text("−0.1s") }
                        OutlinedButton({ subDelay = 0 }) { Text("Reset") }
                        OutlinedButton({ subDelay += 100 }) { Text("+0.1s") }
                        OutlinedButton({ subDelay += 500 }) { Text("+0.5s") }
                    }
                    Text("Subtitle size", color = Dim, fontSize = 12.sp)
                    Slider(subSize, { subSize = it }, valueRange = 0.03f..0.10f)
                    Text("Subtitle position", color = Dim, fontSize = 12.sp)
                    Slider(subBottom, { subBottom = it }, valueRange = 0.0f..0.30f)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Dark background", Modifier.weight(1f)); Switch(subBg, { subBg = it }) }
                }
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
