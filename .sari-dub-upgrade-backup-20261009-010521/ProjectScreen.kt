package com.saridub.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TABS = listOf("Overview", "Setup", "Subtitles", "Voices", "Process", "Export")

@Composable
fun ProjectScreen(id: String, nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SariApp
    val progress by ProcessingState.progress.collectAsState()
    val running = progress.running && progress.projectId == id
    var p by remember { mutableStateOf<Project?>(null) }
    var tab by remember { mutableStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var rev by remember { mutableStateOf(0) }

    LaunchedEffect(id) { p = withContext(Dispatchers.IO) { app.store.load(id) } }
    // While the service owns the project, only re-read it (never write from UI).
    LaunchedEffect(running) { while (running) { delay(2000); p = withContext(Dispatchers.IO) { app.store.load(id) } } ; if (!running && p != null) p = withContext(Dispatchers.IO) { app.store.load(id) } }
    fun save() { p?.let { if (!running) { app.store.save(it); dirty = false } } }
    DisposableEffect(Unit) { onDispose { if (dirty && !ProcessingState.progress.value.running) p?.let { app.store.save(it) } } }

    val pr = p ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }

    Column(Modifier.fillMaxSize()) {
        TopBar(pr.title, nav)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TABS.forEachIndexed { i, t -> Pill(t, tab == i) { tab = i } }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            when (tab) {
                0 -> OverviewTab(pr, nav)
                1 -> SetupTab(pr, running, onChange = { dirty = true; rev++ }, save = ::save)
                2 -> SubtitlesTab(pr, running, app.store) { dirty = true }
                3 -> VoicesTab(pr, running) { dirty = true; rev++ }
                4 -> ProcessTab(pr, progress, running, app.store, ::save)
                5 -> ExportTab(pr, app.store)
            }
        }
    }
}

@Composable
fun OverviewTab(p: Project, nav: Nav) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SCard { Text("Media", fontWeight = FontWeight.Bold); Text(p.info, color = Dim, fontSize = 13.sp) }
        SCard {
            Text("Status", fontWeight = FontWeight.Bold)
            Text("Audio extracted: ${p.extractDone}\nAnalysed (speakers/timing): ${p.analysisDone}\nTranslated: ${p.translateDone}\nDub chunks: ${p.ready.size}/${p.chunkCount}", color = Dim, fontSize = 13.sp)
            if (p.failed.isNotEmpty()) Text("Failed chunks: ${p.failed.map { it + 1 }}", color = Bad, fontSize = 13.sp)
        }
        GradientButton("Watch", Modifier.fillMaxWidth()) { nav.push(Route.Player(p.id, null, p.title)) }
        if (p.log.isNotEmpty()) SCard { Text("Log", fontWeight = FontWeight.Bold); p.log.takeLast(12).forEach { Text(it, color = Dim, fontSize = 11.sp) } }
    }
}

@Composable
fun SetupTab(p: Project, running: Boolean, onChange: () -> Unit, save: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var src by remember(p.id) { mutableStateOf(p.srcLang) }
    var dst by remember(p.id) { mutableStateOf(p.dstLang) }
    var mode by remember(p.id) { mutableStateOf(p.mode) }
    var chunk by remember(p.id) { mutableStateOf(p.chunkMin) }
    var quality by remember(p.id) { mutableStateOf(p.quality) }
    var duck by remember(p.id) { mutableStateOf(p.duck) }
    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val items = withContext(Dispatchers.IO) { Srt.parse(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) }
            if (items.isEmpty()) { msg = "No subtitle entries found in that file"; return@launch }
            p.segments.clear()
            items.forEachIndexed { i, (a, b, t) -> p.segments.add(Segment(i, a, b, a, b, 0, t)) }
            p.analysisDone = false; p.translateDone = false; p.ready.clear(); p.failed.clear(); p.speakers.clear()
            File(ctx.filesDir, "projects/${p.id}/seg").deleteRecursively(); File(ctx.filesDir, "projects/${p.id}/chunks").deleteRecursively()
            msg = "Imported ${items.size} source lines"; onChange(); save()
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SCard {
            Text("Source dialogue", fontWeight = FontWeight.Bold)
            Text("${p.segments.size} lines loaded. This build has no bundled speech-recognition model, so the original-language .srt is the text source; VAD, voice analysis and diarisation run on the real audio.", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            GradientButton("Import source .srt", Modifier.fillMaxWidth(), enabled = !running) { srtPicker.launch(arrayOf("*/*")) }
            if (msg.isNotBlank()) Text(msg, color = Accent2, fontSize = 12.sp)
        }
        SCard {
            Text("Languages (BCP-47 code)", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(src, { src = it.trim(); p.srcLang = src; onChange() }, Modifier.weight(1f), singleLine = true, label = { Text("Original") }, enabled = !running)
                OutlinedTextField(dst, { dst = it.trim(); p.dstLang = dst; onChange() }, Modifier.weight(1f), singleLine = true, label = { Text("Target") }, enabled = !running)
            }
        }
        SCard {
            Text("Mode", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SUBTITLES" to "Subtitles", "VOICE" to "Voice", "FULL" to "Full dub").forEach { (k, l) -> Pill(l, mode == k) { if (!running) { mode = k; p.mode = k; onChange() } } }
            }
            Text(when (mode) { "SUBTITLES" -> "Translate only; no audio generated."; "VOICE" -> "Translated voices over silence (original dialogue muted)."; else -> "Translated voices over the original audio, ducked under dialogue." }, color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Chunk length", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5, 10, 20).forEach { c -> Pill("$c min", chunk == c) { if (!running && p.ready.isEmpty()) { chunk = c; p.chunkMin = c; onChange() } } } }
            if (p.ready.isNotEmpty()) Text("Chunk length is locked once chunks exist.", color = Dim, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("Quality", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("FAST", "BALANCED", "HIGH").forEach { q -> Pill(q, quality == q) { if (!running) { quality = q; p.quality = q; onChange() } } } }
            Text("FAST skips the re-synthesis pass used to fit timing.", color = Dim, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Deep ducking of original under dialogue", Modifier.weight(1f)); Switch(duck, { duck = it; p.duck = it; onChange() }, enabled = !running)
            }
        }
        Text("Not available in this build: separate music/SFX stems, non-verbal sound detection, emotion transfer, voice cloning. Original audio is ducked, not separated.", color = Dim, fontSize = 11.sp)
    }
}

@Composable
fun SubtitlesTab(p: Project, running: Boolean, store: ProjectStore, markDirty: () -> Unit) {
    val ctx = LocalContext.current
    val segs = remember(p.id, p.segments.size, p.translateDone) { p.segments.toMutableStateList() }
    if (segs.isEmpty()) { Text("No subtitles yet. Import a source .srt in Setup.", color = Dim); return }
    fun invalidate(s: Segment) {
        val d = File(ctx.filesDir, "projects/${p.id}")
        File(d, "seg/${s.id}_final.wav").delete()
        val c = (s.startMs / p.chunkMs).toInt()
        if (p.ready.remove(c)) File(d, "chunks/chunk_$c.wav").delete()
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(segs, key = { _, s -> s.id }) { i, s ->
            SCard {
                Text("${Srt.time(s.srtStartMs)}  ·  ${p.speakers.getOrNull(s.speaker)?.name ?: "Speaker ${s.speaker + 1}"}", color = Accent2, fontSize = 12.sp)
                OutlinedTextField(s.original, { v -> val n = s.copy(original = v); segs[i] = n; p.segments[i] = n; markDirty() }, Modifier.fillMaxWidth(), label = { Text("Original") }, enabled = !running, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp))
                OutlinedTextField(s.translated, { v -> val n = s.copy(translated = v); segs[i] = n; p.segments[i] = n; invalidate(n); markDirty() }, Modifier.fillMaxWidth(), label = { Text(p.dstLang) }, enabled = !running, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp))
            }
        }
    }
}

@Composable
fun VoicesTab(p: Project, running: Boolean, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val tts = remember { TtsSynth(ctx) }
    var voices by remember { mutableStateOf(listOf<android.speech.tts.Voice>()) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = tts.init(); if (ready) voices = tts.voices(java.util.Locale.forLanguageTag(p.dstLang)) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    if (p.speakers.isEmpty()) { Text("Speakers appear after the analysis stage has run (Process tab).", color = Dim); return }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (voices.isEmpty()) "No offline ${p.dstLang} voices installed — install one in Android TTS settings." else "${voices.size} offline voice(s) available", color = if (voices.isEmpty()) Bad else Ok, fontSize = 12.sp)
        p.speakers.forEachIndexed { i, sp ->
            var name by remember(sp.id) { mutableStateOf(sp.name) }
            var pa by remember(sp.id) { mutableStateOf(sp.pitchAdjust) }
            var rate by remember(sp.id) { mutableStateOf(sp.rate) }
            var vi by remember(sp.id) { mutableStateOf(sp.voiceIndex) }
            SCard {
                OutlinedTextField(name, { name = it; sp.name = it; onChange() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, enabled = !running)
                Text("Original median pitch: ${if (sp.pitchHz > 0) "%.0f Hz".format(sp.pitchHz) else "unknown"}", color = Dim, fontSize = 12.sp)
                Text("Voice: ${if (voices.isEmpty()) "–" else voices[vi.mod(voices.size)].name}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Next voice", false) { if (!running && voices.isNotEmpty()) { vi = (vi + 1) % voices.size; sp.voiceIndex = vi; onChange() } }
                    Pill("Preview", false) {
                        val seg = p.segments.firstOrNull { it.speaker == i && it.translated.isNotBlank() }
                        val base = if (sp.pitchHz > 0) Math.sqrt((sp.pitchHz / 150f).toDouble()).toFloat() else 1f
                        tts.preview(seg?.translated ?: "Test", voices.getOrNull(vi.mod(maxOf(1, voices.size))), (base * pa).coerceIn(0.5f, 2f), rate)
                    }
                    Pill("Reset", false) { if (!running) { pa = 1f; rate = 1f; sp.pitchAdjust = 1f; sp.rate = 1f; name = "Speaker ${i + 1}"; sp.name = name; onChange() } }
                }
                Text("Pitch ×${"%.2f".format(pa)}", fontSize = 12.sp, color = Dim)
                Slider(pa, { pa = it; sp.pitchAdjust = it; onChange() }, valueRange = 0.6f..1.5f, enabled = !running)
                Text("Speaking rate ×${"%.2f".format(rate)}", fontSize = 12.sp, color = Dim)
                Slider(rate, { rate = it; sp.rate = it; onChange() }, valueRange = 0.7f..1.4f, enabled = !running)
            }
        }
        Text("Changes apply to chunks generated afterwards. Edit a subtitle (or Cancel and clear chunks) to regenerate earlier ones. Voices are system TTS voices with per-speaker pitch/rate; they are not clones of the original actors.", color = Dim, fontSize = 11.sp)
    }
}

@Composable
fun ProcessTab(p: Project, pr: Progress, running: Boolean, store: ProjectStore, save: () -> Unit) {
    val ctx = LocalContext.current
    val dev = remember { DeviceProfile.detect(ctx) }
    val need = Storage.requiredBytes(p.durationMs)
    val enough = dev.freeBytes > need
    val mine = pr.projectId == p.id
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SCard {
            Text("Storage", fontWeight = FontWeight.Bold)
            Text("Working space needed ≈ ${Storage.gb(need)}\nAvailable ${Storage.gb(dev.freeBytes)}\n" + if (enough) "Status: enough storage" else "Status: NOT enough — cannot start", color = if (enough) Ok else Bad, fontSize = 13.sp)
        }
        SCard {
            Text(if (running) pr.stage else if (mine) pr.stage else "Ready", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (mine) {
                if (pr.chunks > 0) Text("Chunk ${pr.chunk} / ${pr.chunks}", color = Dim)
                LinearProgressIndicator(progress = { pr.percent / 100f }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent1)
                Text("${pr.percent}%" + (if (pr.etaSec >= 0) "   ETA ${pr.etaSec / 60}m ${pr.etaSec % 60}s" else "") + (if (pr.speedX > 0) "   ${"%.2f".format(pr.speedX)}× realtime" else ""), color = Dim, fontSize = 13.sp)
                if (pr.message.isNotBlank()) Text(pr.message, color = Accent2, fontSize = 12.sp)
                Text("Thermal: ${pr.thermal}", color = if (pr.thermal == Thermal.NORMAL) Dim else Bad, fontSize = 12.sp)
                pr.error?.let { Text(it, color = Bad, fontSize = 13.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!running) GradientButton(if (p.ready.isNotEmpty() || p.extractDone) "Resume" else "Start", Modifier.weight(1f), enabled = enough) {
                save()
                ProcessingState.progress.value = Progress(projectId = p.id, stage = "Starting", running = true)
                ContextCompat.startForegroundService(ctx, Intent(ctx, ProcessingService::class.java).putExtra("id", p.id))
            } else {
                var paused by remember { mutableStateOf(ProcessingState.paused.get()) }
                GradientButton(if (paused) "Resume" else "Pause", Modifier.weight(1f)) { paused = !paused; ProcessingState.paused.set(paused) }
                GradientButton("Cancel", Modifier.weight(1f)) { ProcessingState.cancel() }
            }
        }
        Text("Progress is saved after each chunk. If the app is closed, press Resume to continue from the last completed stage/chunk.", color = Dim, fontSize = 11.sp)
    }
}

@Composable
fun ExportTab(p: Project, store: ProjectStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { o ->
                        when (pending) {
                            "srt_o" -> o.write(Exporter.srt(p, false).toByteArray())
                            "srt_t" -> o.write(Exporter.srt(p, true).toByteArray())
                            "wav" -> Exporter.concatDub(store, p, o)
                            "json" -> o.write(p.toJson().toString(2).toByteArray())
                        }
                    }
                }
                "Exported."
            } catch (e: Exception) { "Export failed: ${e.message}" }
        }
    }
    fun go(kind: String, name: String) { pending = kind; launcher.launch(name) }
    val base = p.title.replace(Regex("[^A-Za-z0-9_-]"), "_")
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GradientButton("Translated subtitles (.srt)", Modifier.fillMaxWidth(), p.translateDone) { go("srt_t", "$base.${p.dstLang}.srt") }
        GradientButton("Original subtitles (.srt)", Modifier.fillMaxWidth(), p.segments.isNotEmpty()) { go("srt_o", "$base.${p.srcLang}.srt") }
        GradientButton("Dubbed audio (.wav, 24 kHz mono)", Modifier.fillMaxWidth(), p.hasDub && p.chunkCount > 0 && p.ready.size == p.chunkCount) { go("wav", "$base.dub.wav") }
        GradientButton("Project data (.json)", Modifier.fillMaxWidth()) { go("json", "$base.project.json") }
        if (msg.isNotBlank()) Text(msg, color = Accent2)
        Text("Not in this build: muxing the dub into a new video file. Play the dub in-app, or mux the exported WAV with the original video using any tool.", color = Dim, fontSize = 11.sp)
    }
}
