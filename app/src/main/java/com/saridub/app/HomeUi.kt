package com.saridub.app

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val NeuBase = Color(0xFFE8EAF0)
val NeuLight = Color(0xFFFFFFFF)
val NeuDark = Color(0xFFA3A9B6)
val SariOrange = Color(0xFFFF7A1A)
val InkDark = Color(0xFF2A2D36)
val InkMid = Color(0xFF6B7080)

/**
 * Soft-UI (neumorphic) surface. Raised: white highlight from top-left and grey shadow to bottom-right.
 * Pressed: a subtle inner shade instead of outer shadows. Shadows use a single framework paint per shape.
 */
fun Modifier.neu(radius: Dp, pressed: Boolean = false): Modifier = drawBehind {
    val r = radius.toPx()
    val w = size.width
    val h = size.height
    if (!pressed) {
        val blur = 16.dp.toPx()
        val off = 7.dp.toPx()
        drawIntoCanvas { c ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = NeuBase.toArgb()
            p.setShadowLayer(blur, off, off, NeuDark.copy(alpha = 0.6f).toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, p)
            p.setShadowLayer(blur, -off, -off, NeuLight.copy(alpha = 0.95f).toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, p)
        }
        drawRoundRect(color = NeuBase, cornerRadius = CornerRadius(r))
    } else {
        drawRoundRect(color = NeuBase, cornerRadius = CornerRadius(r))
        drawRoundRect(
            brush = Brush.linearGradient(listOf(NeuDark.copy(alpha = 0.5f), Color.Transparent), Offset(0f, 0f), Offset(w * 0.7f, h * 0.7f)),
            cornerRadius = CornerRadius(r)
        )
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color.Transparent, NeuLight.copy(alpha = 0.9f)), Offset(w * 0.3f, h * 0.3f), Offset(w, h)),
            cornerRadius = CornerRadius(r)
        )
    }
}

@Composable
fun NeuIcon(icon: ImageVector, desc: String, size: Dp, tint: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(size).neu(size / 2, pressed)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.45f)) }
}

@Composable
fun NeuSearchField(value: String, onValue: (String) -> Unit, onSearch: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().height(56.dp).neu(28.dp, pressed = focused).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, "Search", tint = InkMid, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f),
            singleLine = true,
            interactionSource = src,
            textStyle = TextStyle(color = InkDark, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search any title…", color = InkMid, fontSize = 16.sp)
                    inner()
                }
            }
        )
    }
}

@Composable
fun ChoiceRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().neu(22.dp, pressed)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = SariOrange, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = InkDark, fontSize = 16.sp)
            Text(subtitle, color = InkMid, fontSize = 12.sp)
        }
    }
}

/** Decorative icon layer: fully opaque at the bottom, fading to invisible toward the middle. Never takes touches. */
@Composable
fun DecorLayer(modifier: Modifier) {
    val row1 = listOf(Icons.Default.Tv, Icons.Default.Videocam, Icons.Default.Translate, Icons.Default.Audiotrack)
    val row2 = listOf(Icons.Default.Subtitles, Icons.Default.Forum, Icons.Default.LibraryMusic, Icons.Default.Language)
    Column(
        modifier.fillMaxWidth().height(230.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), blendMode = BlendMode.DstIn)
            }
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        listOf(row1, row2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { Icon(it, null, tint = InkMid.copy(alpha = 0.22f), modifier = Modifier.size(34.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SariApp
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(listOf<Project>()) }
    val addSrc = remember { MutableInteractionSource() }
    val addPressed by addSrc.collectIsPressedAsState()

    LaunchedEffect(showHistory) {
        if (showHistory) projects = withContext(Dispatchers.IO) { app.store.list() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val id = createProject(ctx, uri, MediaAnalyzer.displayName(ctx, uri).substringBeforeLast('.'))
            busy = false
            if (id != null) nav.push(Route.Proj(id))
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF5F6FA), NeuBase)))) {
        DecorLayer(Modifier.align(Alignment.BottomCenter))

        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                NeuIcon(Icons.Default.Settings, "Settings", 46.dp, InkDark) { nav.push(Route.Settings) }
                NeuIcon(Icons.Default.History, "Recent projects", 46.dp, InkDark) { showHistory = true }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "SARI DUB", color = SariOrange, fontWeight = FontWeight.Black, fontSize = 28.sp,
                letterSpacing = 1.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            NeuSearchField(query, { query = it }) {
                if (query.isNotBlank()) nav.push(Route.Find(query.trim()))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(132.dp).neu(66.dp, addPressed && !busy)
                    .clickable(interactionSource = addSrc, indication = null, enabled = !busy) { showAdd = true },
                contentAlignment = Alignment.Center
            ) {
                if (busy) CircularProgressIndicator(color = SariOrange, modifier = Modifier.size(40.dp))
                else Icon(Icons.Default.Add, "Add video or manga", tint = SariOrange, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(200.dp))
        }
    }

    if (showAdd) ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = NeuBase) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = InkDark)
            ChoiceRow(Icons.Default.Movie, "Video", "Translate and dub a video file") {
                showAdd = false
                picker.launch(arrayOf("video/*", "audio/*", "application/x-matroska"))
            }
            ChoiceRow(Icons.Default.MenuBook, "Manga / Comic", "Detect text, translate pages and export") {
                showAdd = false
                nav.push(Route.Manga)
            }
        }
    }

    if (showHistory) ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = NeuBase) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recent projects", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = InkDark)
            if (projects.isEmpty()) Text("No projects yet. Add a video to start.", color = InkMid)
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(projects, key = { it.id }) { p ->
                    ChoiceRow(Icons.Default.Movie, p.title, "${p.srcLang} → ${p.dstLang} · ${fmtTime(p.durationMs)}") {
                        showHistory = false
                        nav.push(Route.Proj(p.id))
                    }
                }
            }
        }
    }
}
