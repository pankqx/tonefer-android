package com.pankqx.tonefer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pankqx.tonefer.R
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.data.AppSettings
import com.pankqx.tonefer.data.Backdrop
import com.pankqx.tonefer.data.ImageQuality
import com.pankqx.tonefer.data.TextSize
import com.pankqx.tonefer.data.ThemeMode
import com.pankqx.tonefer.data.Typeface
import com.pankqx.tonefer.data.WindowStyle
import com.pankqx.tonefer.net.Conn
import com.pankqx.tonefer.ui.components.CheckRow
import com.pankqx.tonefer.ui.components.ChoiceRow
import com.pankqx.tonefer.ui.components.DashedDivider
import com.pankqx.tonefer.ui.components.Label
import com.pankqx.tonefer.ui.components.MiniIcon
import com.pankqx.tonefer.ui.components.RetroButton
import com.pankqx.tonefer.ui.components.RetroWindow
import com.pankqx.tonefer.ui.components.Rule
import com.pankqx.tonefer.ui.components.backdrop
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import kotlinx.coroutines.launch

/** Shared frame for secondary screens: menu bar with a back arrow, scrolling windows below. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tone = LocalTone.current
    Column(Modifier.fillMaxSize().backdrop()) {
        Column(Modifier.background(tone.paper)) {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniIcon(R.drawable.ic_back, "Back", tone.ink, onBack)
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = ts(16f))
            }
            Box(Modifier.fillMaxWidth().height(Rule).background(tone.ink))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 17.dp, top = 18.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsScreen(app: ToneferApp, onBack: () -> Unit, onAbout: () -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val s by app.settings.state.collectAsState()
    val conn by app.client.conn.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmUnpair by remember { mutableStateOf(false) }
    fun set(f: (AppSettings) -> AppSettings) { scope.launch { app.settings.update(f) } }

    SubScreen("Settings", onBack) {
        RetroWindow("Appearance", Modifier.fillMaxWidth()) {
            ChoiceRow("Theme", ThemeMode.entries.map { it to it.label }, s.theme, { v -> set { it.copy(theme = v) } })
            DashedDivider()
            ChoiceRow("Text size", TextSize.entries.map { it to it.label }, s.textSize, { v -> set { it.copy(textSize = v) } })
            DashedDivider()
            ChoiceRow("Typeface", Typeface.entries.map { it to it.label }, s.typeface, { v -> set { it.copy(typeface = v) } },
                subtitle = "Mono is the classic typewriter look")
            DashedDivider()
            ChoiceRow("Background", Backdrop.entries.map { it to it.label }, s.backdrop, { v -> set { it.copy(backdrop = v) } })
            DashedDivider()
            ChoiceRow("Windows", WindowStyle.entries.map { it to it.label }, s.windowStyle, { v -> set { it.copy(windowStyle = v) } },
                subtitle = "Classic adds hard drop shadows and pressable buttons")
            Spacer(Modifier.height(8.dp))
            RetroButton("Reset appearance", onClick = { scope.launch { app.settings.resetAppearance() } })
        }

        RetroWindow("Transfers", Modifier.fillMaxWidth()) {
            CheckRow("Save received photos & videos", "Into Pictures/Tonefer and Movies/Tonefer", s.autoSavePhotos) { v -> set { it.copy(autoSavePhotos = v) } }
            DashedDivider()
            CheckRow("Save other received files", "Into Download/Tonefer", s.autoSaveFiles) { v -> set { it.copy(autoSaveFiles = v) } }
            DashedDivider()
            CheckRow("Copy received notes automatically", "Notes from the laptop go straight to your clipboard", s.autoCopyText) { v -> set { it.copy(autoCopyText = v) } }
            DashedDivider()
            ChoiceRow(
                "Photo quality when sending", ImageQuality.entries.map { it to it.label }, s.imageQuality, { v -> set { it.copy(imageQuality = v) } },
                subtitle = when (s.imageQuality) {
                    ImageQuality.ORIGINAL -> "Sends photos untouched"
                    ImageQuality.HIGH -> "Resizes to 2560 px, about 60% smaller"
                    ImageQuality.BALANCED -> "Resizes to 1600 px. Fastest."
                },
            )
        }

        RetroWindow("Notifications", Modifier.fillMaxWidth()) {
            CheckRow("Notify when something arrives", "While Tonefer is in the background", s.notify) { v -> set { it.copy(notify = v) } }
            DashedDivider()
            CheckRow("Vibrate", null, s.vibrate) { v -> set { it.copy(vibrate = v) } }
        }

        RetroWindow("Connection", Modifier.fillMaxWidth()) {
            CheckRow(
                "Stay connected in background",
                "Receive while the app is closed. Shows a small persistent notification, as Android requires.",
                s.background,
            ) { v -> set { it.copy(background = v) } }
            DashedDivider()
            Column(Modifier.padding(vertical = 10.dp)) {
                Label("Paired laptop")
                Spacer(Modifier.height(4.dp))
                Text(s.pairing?.laptop?.ifBlank { null } ?: "Unknown", fontWeight = FontWeight.Medium)
                Text(
                    "${s.pairing?.host}:${s.pairing?.port}  ·  " + when (conn) {
                        is Conn.Online -> "connected"
                        Conn.Searching -> "searching"
                        else -> "not connected"
                    },
                    color = tone.muted, fontSize = ts(12f),
                )
            }
            Row {
                RetroButton("Find laptop", icon = R.drawable.ic_refresh, modifier = Modifier.weight(1f), onClick = {
                    app.client.reconnect()
                    Toast.makeText(context, "Searching for your laptop…", Toast.LENGTH_SHORT).show()
                })
                Spacer(Modifier.width(10.dp))
                RetroButton("Unpair", danger = true, modifier = Modifier.weight(1f), onClick = { confirmUnpair = true })
            }
        }

        RetroWindow("Storage", Modifier.fillMaxWidth()) {
            Text("Received files are kept in a private cache so photos open instantly. Saved copies in your gallery aren't affected.",
                color = tone.muted, fontSize = ts(12.5f))
            Spacer(Modifier.height(12.dp))
            RetroButton("Clear cache", icon = R.drawable.ic_trash, onClick = {
                app.client.clearCache()
                Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
            })
        }

        RetroButton("About Tonefer", modifier = Modifier.fillMaxWidth(), onClick = onAbout)
    }

    if (confirmUnpair) Dialog(onDismissRequest = { confirmUnpair = false }) {
        RetroWindow("Unpair", onClose = { confirmUnpair = false }) {
            Text("Unpair this phone?", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("You'll need to scan the laptop's code again to reconnect. History on the laptop is kept.", color = tone.muted, fontSize = ts(13f))
            Spacer(Modifier.height(16.dp))
            Row {
                RetroButton("Cancel", modifier = Modifier.weight(1f), onClick = { confirmUnpair = false })
                Spacer(Modifier.width(10.dp))
                RetroButton("Unpair", primary = true, modifier = Modifier.weight(1f), onClick = {
                    confirmUnpair = false
                    app.client.stop()
                    app.client.clearCache()
                    scope.launch { app.settings.update { it.copy(pairing = null) } }
                })
            }
        }
    }
}
