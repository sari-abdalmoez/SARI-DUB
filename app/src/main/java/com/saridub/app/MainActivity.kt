package com.saridub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
        setContent { SariTheme { AppRoot() } }
    }
}

sealed class Screen {
    object Home : Screen()
    data class Search(val q: String) : Screen()
    data class Details(val title: Title) : Screen()
    data class Player(val title: Title, val items: List<Playable>) : Screen()
    object Manga : Screen()
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
                onOpen = { push(Screen.Details(it)) },
                onManga = { push(Screen.Manga) },
                onSettings = { push(Screen.Settings) }
            )
            is Screen.Search -> SearchScreen(s.q, onOpen = { push(Screen.Details(it)) }, onBack = { pop() })
            is Screen.Details -> DetailsScreen(
                s.title,
                onWatch = { items -> push(Screen.Player(s.title, items)) },
                onBack = { pop() }
            )
            is Screen.Player -> PlayerScreen(s.title, s.items, onBack = { pop() })
            Screen.Manga -> MangaScreen(onBack = { pop() })
            Screen.Settings -> SettingsScreen(onBack = { pop() })
        }
    }
}
