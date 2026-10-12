package com.saridub.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.initialize(applicationContext)
        setContent { SariTheme { AppRoot() } }
    }
}

sealed class Screen {
    object Home : Screen()
    data class Search(val q: String) : Screen()
    data class Details(val title: Title) : Screen()
    data class Player(val title: Title, val items: List<Playable>) : Screen()
    data class Manga(val uris: List<Uri>) : Screen()
    data class VideoTranslate(val uri: Uri) : Screen()
    object Settings : Screen()
}

@Composable
fun AppRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val s = stack.last()) {
            Screen.Home -> HomeScreen(
                onSearch = { push(Screen.Search(it)) },
                onVideoPicked = { uri -> push(Screen.VideoTranslate(uri)) },
                onMangaPicked = { uris -> push(Screen.Manga(uris)) },
                onHistory = { push(Screen.Search("")) },
                onSettings = { push(Screen.Settings) }
            )
            is Screen.VideoTranslate -> VideoTranslationScreen(
                uri = s.uri,
                onBack = { pop() },
                onTranslate = { language ->
                    AppSettings.targetLanguage = language
                    push(Screen.Player(localTitle(), listOf(localPlayable(s.uri))))
                }
            )
            is Screen.Search -> SearchScreen(s.q, onOpen = { push(Screen.Details(it)) }, onBack = { pop() })
            is Screen.Details -> DetailsScreen(
                title = s.title,
                onWatch = { items -> push(Screen.Player(s.title, items)) },
                onOpenRelated = { push(Screen.Details(it)) },
                onBack = { pop() }
            )
            is Screen.Player -> PlayerScreen(s.title, s.items, onBack = { pop() })
            is Screen.Manga -> MangaScreen(s.uris, onBack = { pop() })
            Screen.Settings -> SettingsScreen(onBack = { pop() })
        }
    }
}

private fun localTitle(): Title = Title(
    key = "local:${System.nanoTime()}",
    title = "فيديو من الجهاز",
    year = null,
    kind = "MOVIE",
    rating = 0.0,
    poster = null,
    overview = "ملف فيديو من جهازك.",
    source = "Device"
)

private fun localPlayable(uri: Uri): Playable =
    Playable(uri.toString(), "ملف الجهاز", null, "ملف من الجهاز")
