package com.saridub.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Light neumorphic palette from the supplied home design.
private val LightBg = Color(0xFFE8ECF2)
private val LightShade = Color(0xFFB7C1D0)
private val BrandOrange = Color(0xFFFF6B00)
private val InkDark = Color(0xFF2D3748)
private val Muted = Color(0xFF8A94A6)

@Composable
private fun LightCircle(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp)
            .shadow(8.dp, CircleShape, ambientColor = LightShade, spotColor = LightShade)
            .clip(CircleShape).background(LightBg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = InkDark, modifier = Modifier.size(22.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearch: (String) -> Unit,
    onVideoPicked: (Uri) -> Unit,
    onMangaPicked: (List<Uri>) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSheet by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        showSheet = false
        if (uri != null) onVideoPicked(uri)
    }
    val mangaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        showSheet = false
        if (uris.isNotEmpty()) onMangaPicked(uris)
    }

    Column(
        Modifier.fillMaxSize().background(LightBg).statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LightCircle(Icons.Filled.Refresh, "History", onHistory)
            LightCircle(Icons.Filled.Settings, "Settings", onSettings)
        }
        Spacer(Modifier.height(18.dp))
        Text("SARI DUB", color = BrandOrange, fontWeight = FontWeight.Black, fontSize = 28.sp)
        Spacer(Modifier.height(22.dp))

        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(LightBg)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = InkDark, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("...Search any title", color = Muted, fontSize = 16.sp)
                        inner()
                    }
                }
            )
            Icon(Icons.Filled.Search, null, tint = Muted, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(170.dp)
                .shadow(14.dp, CircleShape, ambientColor = LightShade, spotColor = LightShade)
                .clip(CircleShape).background(LightBg)
                .clickable { showSheet = true },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Add, "Add", tint = BrandOrange, modifier = Modifier.size(72.dp)) }
        Spacer(Modifier.weight(1f))
        DecorativeIcons()
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = LightBg) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Add", color = InkDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                SheetOption("Video", "Translate and dub a video from this device", Icons.Filled.PlayArrow) {
                    videoPicker.launch("video/*")
                }
                SheetOption("Manga / Comic", "Detect text, translate pages and save results", Icons.Filled.Info) {
                    mangaPicker.launch("image/*")
                }
            }
        }
    }
}

@Composable
private fun SheetOption(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = LightShade, spotColor = LightShade)
            .clip(RoundedCornerShape(20.dp)).background(LightBg)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFE6D5)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = BrandOrange, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = InkDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = Color(0xFF6B7385), fontSize = 12.sp)
        }
    }
}

@Composable
private fun DecorativeIcons() {
    val icons = listOf(
        Icons.Filled.Star, Icons.Filled.Home, Icons.Filled.PlayArrow, Icons.Filled.Info,
        Icons.Filled.Refresh, Icons.Filled.Check, Icons.Filled.List, Icons.Filled.Search
    )
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        icons.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { Icon(it, null, tint = InkDark.copy(alpha = 0.18f), modifier = Modifier.size(28.dp)) }
            }
        }
    }
}
