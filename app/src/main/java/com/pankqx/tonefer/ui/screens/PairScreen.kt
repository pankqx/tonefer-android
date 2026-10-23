package com.pankqx.tonefer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pankqx.tonefer.R
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.data.Pairing
import com.pankqx.tonefer.ui.components.Brand
import com.pankqx.tonefer.ui.components.DashedDivider
import com.pankqx.tonefer.ui.components.DevicesMark
import com.pankqx.tonefer.ui.components.RetroButton
import com.pankqx.tonefer.ui.components.RetroWindow
import com.pankqx.tonefer.ui.components.backdrop
import com.pankqx.tonefer.ui.components.clickableNoRipple
import com.pankqx.tonefer.ui.components.ruled
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import kotlinx.coroutines.launch

@Composable
fun PairScreen(app: ToneferApp, unpaired: Boolean = false, onHelp: () -> Unit) {
    val tone = LocalTone.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }

    fun accept(raw: String) {
        val p = Pairing.parse(raw)
        if (p == null) {
            error = "That isn't a Tonefer pairing code. Scan the code shown in Tonefer on your laptop."
            return
        }
        error = null
        scope.launch {
            app.settings.update { it.copy(pairing = p) }
            app.client.reconnect()
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::accept)
    }

    Box(Modifier.fillMaxSize().backdrop()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            RetroWindow(title = if (unpaired) "Pair again" else "Welcome", modifier = Modifier.fillMaxWidth()) {
                DevicesMark(linked = !unpaired)
                Spacer(Modifier.height(18.dp))
                Text(
                    if (unpaired) "This phone was unpaired." else "Hello, phone.",
                    fontSize = ts(24f), fontWeight = FontWeight.SemiBold, lineHeight = ts(30f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (unpaired) "The laptop changed its key. Scan the new code to keep transferring."
                    else "Pair once with Tonefer on your laptop. After that, notes, photos and files move between them instantly.",
                    color = tone.muted,
                )
                Spacer(Modifier.height(16.dp))
                Steps(
                    "Open Tonefer on your laptop",
                    "Connect: phone hotspot, USB tethering or the same Wi‑Fi",
                    "Scan the code in the “Pair a phone” window",
                )
                Spacer(Modifier.height(18.dp))
                RetroButton(
                    "Scan pairing code", primary = true, icon = R.drawable.ic_qr,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Point at the code on your laptop")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (!pasting) {
                    RetroButton("Paste pairing link", icon = R.drawable.ic_link, modifier = Modifier.fillMaxWidth(), onClick = { pasting = true })
                } else {
                    BasicTextField(
                        value = link,
                        onValueChange = { link = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = tone.family, fontSize = ts(13f), color = tone.ink),
                        cursorBrush = SolidColor(tone.ink),
                        modifier = Modifier.fillMaxWidth().background(tone.paper).ruled().padding(12.dp),
                        decorationBox = { inner ->
                            if (link.isEmpty()) Text("http://10.42.0.15:8765/#k=…", color = tone.muted, fontSize = ts(13f))
                            inner()
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        RetroButton("Cancel", onClick = { pasting = false; link = "" }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        RetroButton("Pair", primary = true, enabled = link.isNotBlank(), onClick = { accept(link) }, modifier = Modifier.weight(1f))
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = tone.danger, fontSize = ts(12.5f))
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand()
                Text("Instructions", color = tone.muted, fontSize = ts(12.5f),
                    modifier = Modifier.padding(8.dp).then(Modifier.clickableNoRipple(onHelp)))
            }
        }
    }
}

@Composable
fun Steps(vararg steps: String) {
    val tone = LocalTone.current
    Column {
        steps.forEachIndexed { i, s ->
            DashedDivider()
            Row(Modifier.padding(vertical = 9.dp)) {
                Text("${i + 1}.", color = tone.muted, modifier = Modifier.width(26.dp))
                Text(s, fontSize = ts(13f))
            }
        }
        DashedDivider()
    }
}
