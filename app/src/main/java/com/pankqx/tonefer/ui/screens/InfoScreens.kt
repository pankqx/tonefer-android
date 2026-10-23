package com.pankqx.tonefer.ui.screens

import com.pankqx.tonefer.BuildConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pankqx.tonefer.ui.components.Brand
import com.pankqx.tonefer.ui.components.DashedDivider
import com.pankqx.tonefer.ui.components.DevicesMark
import com.pankqx.tonefer.ui.components.Label
import com.pankqx.tonefer.ui.components.RetroWindow
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts

@Composable
private fun Rows(vararg rows: Pair<String, String>) {
    val tone = LocalTone.current
    Column {
        rows.forEachIndexed { i, (title, body) ->
            if (i > 0) DashedDivider()
            Column(Modifier.padding(vertical = 10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(body, color = tone.muted, fontSize = ts(13f), lineHeight = ts(19f))
            }
        }
    }
}

@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    SubScreen("Instructions", onBack) {
        RetroWindow("01  Connect", Modifier.fillMaxWidth()) {
            Rows(
                "USB tethering (fastest)" to "Plug the phone into the laptop and turn on Settings → Hotspot & tethering → USB tethering.",
                "Phone hotspot" to "Turn on your hotspot and join it from the laptop. After the one-click setup on Ubuntu, the laptop joins by itself.",
                "Same Wi‑Fi" to "Any network both devices share also works. Some college or public networks block this.",
            )
        }
        RetroWindow("02  Pair once", Modifier.fillMaxWidth()) {
            Rows(
                "Open Tonefer on the laptop" to "The “Pair a phone” window shows a QR code.",
                "Scan it here" to "Tap “Scan pairing code”. If the camera can't read it, copy the link from the laptop and use “Paste pairing link”.",
                "That's it" to "Tonefer finds the laptop by itself from now on, even when its address changes.",
            )
        }
        RetroWindow("03  Send & receive", Modifier.fillMaxWidth()) {
            Rows(
                "From any app" to "Share → Tonefer. It's sent instantly without opening the app.",
                "From Tonefer" to "Type a note, or tap + for photos, videos and files.",
                "Received notes" to "Tap the copy icon, use the notification's Copy button, or turn on auto-copy in Settings.",
                "Received photos & files" to "Saved automatically to Pictures/Tonefer (change this in Settings), or tap ↓ to save and the link icon to share.",
            )
        }
        RetroWindow("04  Customise", Modifier.fillMaxWidth()) {
            Rows(
                "Settings" to "Menu ⋮ → Settings: theme, text size, typeface, background, window style, auto-save, auto-copy, photo quality, notifications and background connection.",
            )
        }
        RetroWindow("05  Troubleshooting", Modifier.fillMaxWidth()) {
            Rows(
                "“Can't reach your laptop”" to "Make sure Tonefer is running on the laptop and both devices are on the same hotspot, USB link or Wi‑Fi. Then tap Retry.",
                "Still not connecting" to "On Ubuntu, open the firewall: sudo ufw allow 8765/tcp. On Windows, mark the hotspot as a Private network.",
                "“This phone was unpaired”" to "Someone used “Unpair all phones” on the laptop. Scan the new code.",
            )
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val tone = LocalTone.current
    val version = BuildConfig.VERSION_NAME
    SubScreen("About", onBack) {
        RetroWindow("Tonefer", Modifier.fillMaxWidth()) {
            Row { DevicesMark() }
            Spacer(Modifier.height(14.dp))
            Brand()
            Spacer(Modifier.height(6.dp))
            Text("Version $version", color = tone.muted, fontSize = ts(12.5f))
            Spacer(Modifier.height(12.dp))
            Text("Private, offline transfer between your phone and your laptop.", fontSize = ts(13.5f))
        }
        RetroWindow("Privacy", Modifier.fillMaxWidth()) {
            Rows(
                "No cloud, no account" to "Everything moves directly between your devices over your own hotspot, cable or Wi‑Fi. Tonefer has no servers.",
                "End-to-end encrypted" to "Each note and file is sealed with XSalsa20‑Poly1305 using a 256‑bit key shared once through the QR code.",
                "No tracking" to "No analytics, ads or crash reporters. The pairing key is excluded from backups.",
            )
        }
        RetroWindow("Credits", Modifier.fillMaxWidth()) {
            Label("Open-source components")
            Spacer(Modifier.height(6.dp))
            Text(
                "IBM Plex Mono — SIL Open Font License 1.1\nOkHttp — Apache 2.0\nCoil — Apache 2.0\nZXing Android Embedded — Apache 2.0\nAndroidX & Jetpack Compose — Apache 2.0",
                color = tone.muted, fontSize = ts(12.5f), lineHeight = ts(19f),
            )
            Spacer(Modifier.height(14.dp))
            Row {
                Text("© 2026 Pank. All rights reserved.", color = tone.muted, fontSize = ts(12f))
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
