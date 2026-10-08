package com.saridub.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

@Composable
fun MangaScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<File>>(emptyList()) }
    var target by remember { mutableStateOf("ar") }
    var expanded by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pages = uris
        outputs = emptyList()
        status = if (uris.isEmpty()) "No pages selected" else "${uris.size} page(s) ready"
        uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && outputs.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(ctx, tree)
                var ok = 0
                outputs.forEach { f ->
                    val doc = dir?.createFile("image/png", f.name)
                    if (doc != null) {
                        ctx.contentResolver.openOutputStream(doc.uri)?.use { out -> f.inputStream().use { it.copyTo(out, 64 * 1024) } }
                        ok++
                    }
                }
                withContext(Dispatchers.Main) { status = "Exported $ok/${outputs.size} page(s)" }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Manga / Comic", modifier = Modifier.weight(1f), fontSize = 21.sp)
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SCard {
                Text("Automatic source language", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("OCR → language detection → translation. The page is processed one at a time to keep RAM low.", color = Dim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Box {
                    GradientButton("Translate to ${LANGS.firstOrNull { it.first == target }?.second ?: target}", enabled = !running) { expanded = true }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        LANGS.forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { target = code; expanded = false })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                GradientButton("Select pages", Modifier.weight(1f), !running) { picker.launch(arrayOf("image/*")) }
                GradientButton("Translate", Modifier.weight(1f), !running && pages.isNotEmpty()) {
                    scope.launch {
                        running = true; outputs = emptyList(); current = 0; total = pages.size; status = "Starting…"
                        val outList = ArrayList<File>()
                        runCatching {
                            for ((i, uri) in pages.withIndex()) {
                                coroutineContext.ensureActive()
                                current = i + 1
                                status = "Processing page ${i + 1}/${pages.size}"
                                val out = withContext(Dispatchers.IO) { File(ctx.filesDir, "manga/${UUID.randomUUID()}/page_${i + 1}.png") }
                                val result = MangaProcessor.process(ctx, uri, target, out)
                                outList += result.output
                            }
                        }.onSuccess { status = "Completed ${outList.size}/${pages.size} page(s)" }
                         .onFailure { status = "Failed: ${it.message}" }
                        outputs = outList
                        running = false
                    }
                }
            }
            if (running) {
                LinearProgressIndicator(progress = { if (total == 0) 0f else current.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text("Page $current / $total", color = Dim, fontSize = 12.sp)
            }
            if (status.isNotBlank()) Text(status, color = Accent2, fontSize = 12.sp)
            if (outputs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GradientButton("Export all", Modifier.weight(1f)) { folderPicker.launch(null) }
                    GradientButton("Clear", Modifier.weight(1f)) { outputs = emptyList(); status = "" }
                }
            }
            if (pages.isNotEmpty()) Text("Selected pages: ${pages.size}", color = Dim, fontSize = 12.sp)
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(outputs, key = { it.absolutePath }) { f ->
                SCard {
                    AsyncImage(model = f, contentDescription = f.name, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(f.name, color = Dim, fontSize = 11.sp)
                }
            }
        }
    }
}
