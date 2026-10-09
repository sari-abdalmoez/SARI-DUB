package com.saridub.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class Route {
    object Home : Route()
    data class Find(val q: String) : Route()
    object Settings : Route()
    data class Proj(val id: String) : Route()
    data class Player(val id: String?, val url: String?, val title: String) : Route()
}

class Nav(val push: (Route) -> Unit, val back: () -> Unit)

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { SariTheme { Root() } }
    }
}

@Composable
fun Root() {
    var stack by remember { mutableStateOf(listOf<Route>(Route.Home)) }
    val nav = Nav({ stack = stack + it }, { if (stack.size > 1) stack = stack.dropLast(1) })
    BackHandler(stack.size > 1) { nav.back() }
    val onHome = stack.last() is Route.Home
    Box(Modifier.fillMaxSize().background(if (onHome) NeuBase else Bg).then(if (onHome) Modifier else Modifier.statusBarsPadding())) {
        when (val r = stack.last()) {
            Route.Home -> HomeScreen(nav)
            is Route.Find -> SearchScreen(nav, r.q)
            Route.Settings -> SettingsScreen(nav)
            is Route.Proj -> ProjectScreen(r.id, nav)
            is Route.Player -> PlayerScreen(r, nav)
        }
    }
}

@Composable
fun TopBar(title: String, nav: Nav?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav != null) IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = if (nav == null) 8.dp else 0.dp))
        actions()
    }
}

/** Creates a project from any readable media Uri (content:// or http(s)://), analysing it first. */
suspend fun createProject(ctx: Context, uri: Uri, title: String): String? {
    val app = ctx.applicationContext as SariApp
    val info = try { MediaAnalyzer.analyze(ctx, uri) } catch (e: Exception) {
        withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "Unsupported or unreadable media: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
        return null
    }
    if (info.audio.isEmpty()) {
        withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "No audio track found", android.widget.Toast.LENGTH_LONG).show() }
        return null
    }
    val p = Project(UUID.randomUUID().toString()).apply {
        this.title = title; sourceUri = uri.toString(); durationMs = info.durationMs
        this.info = info.describe(); audioTrack = info.audio.first().index
    }
    withContext(Dispatchers.IO) { app.store.save(p) }
    return p.id
}

@Composable
fun SearchScreen(nav: Nav, initial: String = "") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = remember { SearchBridge.hub() }
    var q by remember { mutableStateOf(initial) }
    var out by remember { mutableStateOf<SearchHub.Outcome?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(initial) {
        if (initial.isNotBlank()) { loading = true; out = hub.search(initial); loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Search legal sources", nav)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title or direct URL") })
            GradientButton(if (loading) "Searching…" else "Search", Modifier.fillMaxWidth(), enabled = !loading && q.isNotBlank()) {
                scope.launch { loading = true; out = hub.search(q); loading = false }
            }
            Text("Searches public-domain/authorised sources only (Internet Archive) plus URLs you provide. Nothing from your device is uploaded.", color = Dim, fontSize = 12.sp)
            out?.errors?.forEach { Text(it, color = Bad, fontSize = 12.sp) }
            if (status.isNotBlank()) Text(status, color = Accent2, fontSize = 13.sp)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (out != null && out!!.titles.isEmpty() && !loading) item { Text("No results. Try another spelling or add a year.", color = Dim) }
            items(out?.titles ?: emptyList(), key = { it.key }) { r ->
                SCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (r.posterUrl != null) coil.compose.AsyncImage(r.posterUrl, null, Modifier.size(64.dp, 90.dp))
                        Column(Modifier.padding(start = if (r.posterUrl != null) 12.dp else 0.dp).weight(1f)) {
                            Text(r.title, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(listOfNotNull(
                                r.year.takeIf { it > 0 }?.toString(),
                                r.runtimeMin.takeIf { it > 0 }?.let { "${it} min" },
                                r.kind.lowercase().replaceFirstChar { it.uppercase() },
                                r.rating.takeIf { it > 0f }?.let { "★ %.1f".format(it) }
                            ).joinToString(" · "), color = Dim, fontSize = 12.sp)
                            Text(r.source, color = Accent2, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GradientButton("Open", Modifier.fillMaxWidth()) {
                        status = "Searching for a playable source…"
                        scope.launch {
                            val u = runCatching { ArchiveProvider().resolveFile(r.key.removePrefix("ia:")) }.getOrNull()
                            status = if (u == null) "No playable file found for this title" else ""
                            if (u != null) nav.push(Route.Player(null, u, r.title))
                        }
                    }
                }
            }
        }
    }
}

val LANGS = listOf("en" to "English", "ar" to "Arabic", "fr" to "French", "es" to "Spanish", "de" to "German", "tr" to "Turkish",
    "ru" to "Russian", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "hi" to "Hindi", "it" to "Italian", "pt" to "Portuguese", "fa" to "Persian", "ur" to "Urdu")

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dev = remember { DeviceProfile.detect(ctx) }
    val thermal = remember { ThermalMonitor(ctx) }
    var th by remember { mutableStateOf(Thermal.NORMAL) }
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    val working = remember { mutableStateMapOf<String, Boolean>() }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) { th = thermal.level(); delay1() }
    }
    LaunchedEffect(Unit) { LANGS.forEach { (t, _) -> installed[t] = runCatching { TranslationModels.isDownloaded(t) }.getOrDefault(false) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Settings", nav)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SCard {
                Text("Device", fontWeight = FontWeight.Bold)
                Text("RAM ${dev.ramMb} MB · ${dev.cores} cores · ${dev.abi} · Android API ${dev.sdk}", color = Dim, fontSize = 13.sp)
                Text("Free storage ${Storage.gb(dev.freeBytes)} · Thermal: $th", color = Dim, fontSize = 13.sp)
                Text("Recommended quality: ${dev.recommended}", color = Accent2, fontWeight = FontWeight.SemiBold)
            }
            SCard {
                Text("Models", fontWeight = FontWeight.Bold)
                Text("Translation models (ML Kit, ~30 MB each) run fully on-device after a one-time, explicit download. TTS uses installed Android system voices. No ASR model is bundled in this build.", color = Dim, fontSize = 12.sp)
                if (err.isNotBlank()) Text(err, color = Bad, fontSize = 12.sp)
                LANGS.forEach { (tag, name) ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name); Text(if (installed[tag] == true) "Installed" else "Not installed", color = if (installed[tag] == true) Ok else Dim, fontSize = 12.sp) }
                        if (working[tag] == true) Text("Working…", color = Accent2)
                        else if (tag != "en") {
                            val on = installed[tag] == true
                            Pill(if (on) "Delete" else "Download", false) {
                                scope.launch {
                                    working[tag] = true; err = ""
                                    runCatching { if (on) TranslationModels.delete(tag) else TranslationModels.download(tag) }.onFailure { err = "$name: ${it.message}" }
                                    installed[tag] = runCatching { TranslationModels.isDownloaded(tag) }.getOrDefault(false)
                                    working[tag] = false
                                }
                            }
                        }
                    }
                }
            }
            SCard {
                Text("Privacy", fontWeight = FontWeight.Bold)
                Text("Videos, audio, subtitles and translations stay on this device. The network is used only for legal-source search, resolving URLs you enter, and model downloads you start.", color = Dim, fontSize = 12.sp)
            }
        }
    }
}

private suspend fun delay1() = kotlinx.coroutines.delay(2000)
