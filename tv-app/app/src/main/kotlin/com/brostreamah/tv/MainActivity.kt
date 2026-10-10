package com.brostreamah.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.brostreamah.ItemData

private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Play(val item: ItemData) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var home: HomeModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = (application as App).engine
        home = (application as App).home
        setContent { BroTheme { Root(engine, home, onExit = { finish() }) } }
    }
}

@Composable
private fun Root(engine: Engine, home: HomeModel, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("brostream_tv", Context.MODE_PRIVATE) }
    var adult by remember { mutableStateOf(prefs.getBoolean("adult_ok", false)) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    fun push(screen: Screen) { stack = stack + screen }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!adult) {
            AgeGateScreen(onAccept = { prefs.edit().putBoolean("adult_ok", true).apply(); adult = true }, onExit = onExit)
            return@Box
        }
        BackHandler(enabled = stack.last() !is Screen.Play) { pop() }
        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(home, engine, onPlay = { push(Screen.Play(it)) }, onSearch = { push(Screen.Search) }, onSettings = { push(Screen.Settings) })
            Screen.Search -> SearchScreen(engine, onPlay = { push(Screen.Play(it)) })
            Screen.Settings -> SettingsScreen(engine, home, onBack = { pop() })
            is Screen.Play -> PlayerScreen(engine, screen.item, onClose = { pop() })
        }
    }
}
