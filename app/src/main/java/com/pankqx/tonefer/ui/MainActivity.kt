package com.pankqx.tonefer.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.net.Conn
import com.pankqx.tonefer.ui.screens.AboutScreen
import com.pankqx.tonefer.ui.screens.HomeScreen
import com.pankqx.tonefer.ui.screens.InstructionsScreen
import com.pankqx.tonefer.ui.screens.LOADING_MS
import com.pankqx.tonefer.ui.screens.LoadingScreen
import com.pankqx.tonefer.ui.screens.PairScreen
import com.pankqx.tonefer.ui.screens.SettingsScreen
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ToneferTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ToneferApp
        setContent {
            val settings by app.settings.state.collectAsState()
            ToneferTheme(settings) {
                val dark = LocalTone.current.dark
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                Root(app)
            }
        }
    }
}

private enum class Screen { HOME, SETTINGS, HELP, ABOUT }

@Composable
private fun Root(app: ToneferApp) {
    var booting by rememberSaveable { mutableStateOf(true) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!booting) return@LaunchedEffect
        kotlinx.coroutines.delay(LOADING_MS.toLong())
        // The start-up screen lifts away like a shutter, revealing the app underneath.
        exit.animateTo(1f, tween(520, easing = CubicBezierEasing(.7f, 0f, .2f, 1f)))
        booting = false
    }
    Box(Modifier.fillMaxSize()) {
        Screens(app)
        if (booting) LoadingScreen(
            Modifier
                .graphicsLayer { translationY = -size.height * exit.value }
                // Swallow touches so nothing underneath is tapped while it's up.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
        )
    }
}

@Composable
private fun Screens(app: ToneferApp) {
    val settings by app.settings.state.collectAsState()
    val conn by app.client.conn.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var help by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = screen != Screen.HOME || help) {
        if (help) help = false else screen = if (screen == Screen.ABOUT) Screen.SETTINGS else Screen.HOME
    }

    if (help) { InstructionsScreen(onBack = { help = false }); return }

    val paired = settings.pairing != null
    if (!paired || conn is Conn.Unpaired) {
        PairScreen(app, unpaired = paired && conn is Conn.Unpaired, onHelp = { help = true })
        return
    }
    when (screen) {
        Screen.HOME -> HomeScreen(
            app,
            onSettings = { screen = Screen.SETTINGS },
            onHelp = { help = true },
            onAbout = { screen = Screen.ABOUT },
        )
        Screen.SETTINGS -> SettingsScreen(app, onBack = { screen = Screen.HOME }, onAbout = { screen = Screen.ABOUT })
        Screen.HELP -> InstructionsScreen(onBack = { screen = Screen.HOME })
        Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.SETTINGS })
    }
}
