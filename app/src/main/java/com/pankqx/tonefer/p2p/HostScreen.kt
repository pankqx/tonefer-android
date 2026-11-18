package com.pankqx.tonefer.p2p

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.pankqx.tonefer.R
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.ui.components.IconSquare
import com.pankqx.tonefer.ui.components.Label
import com.pankqx.tonefer.ui.components.MiniIcon
import com.pankqx.tonefer.ui.components.RetroButton
import com.pankqx.tonefer.ui.components.RetroWindow
import com.pankqx.tonefer.ui.components.Rule
import com.pankqx.tonefer.ui.components.StatusLight
import com.pankqx.tonefer.ui.components.backdrop
import com.pankqx.tonefer.ui.components.clickableNoRipple
import com.pankqx.tonefer.ui.components.hardShadow
import com.pankqx.tonefer.ui.components.ruled
import com.pankqx.tonefer.ui.screens.MenuRow
import com.pankqx.tonefer.ui.screens.ProgressBar
import com.pankqx.tonefer.ui.screens.RetroMenu
import com.pankqx.tonefer.ui.screens.Steps
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import com.pankqx.tonefer.util.Files
import com.pankqx.tonefer.util.Hotspot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Phone to phone: shows a QR code for another phone's camera. Once it scans, both phones swap
 * notes, photos, files and folders directly, with no laptop and no app needed on the other phone.
 */
@Composable
fun HostScreen(app: ToneferApp, onBack: () -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val host = app.host
    val state by host.state.collectAsState()
    val items by host.items.collectAsState()
    val transfers by host.transfers.collectAsState()
    val message by host.messages.collectAsState()
    var showCode by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // The hotspot is often switched on after opening this screen: keep the address fresh.
    LaunchedEffect(state.running) {
        while (state.running) { host.refreshAddresses(); delay(2500) }
    }
    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); host.consumeMessage() }
    }
    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.lastIndex) }
    // As soon as the other phone connects, swap the big code for the conversation.
    LaunchedEffect(state.guests) { if (state.guests > 0) showCode = false }
    val view = LocalView.current
    DisposableEffect(state.running && state.guests == 0 || transfers.isNotEmpty()) {
        view.keepScreenOn = state.running && state.guests == 0 || transfers.isNotEmpty()
        onDispose { view.keepScreenOn = false }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { host.sendUris(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { host.sendUris(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            Toast.makeText(context, "Packing the folder…", Toast.LENGTH_SHORT).show()
            host.sendFolder(tree)
        }
    }
    fun hotspot() { if (!Hotspot.open(context)) Toast.makeText(context, "Open Settings → Hotspot", Toast.LENGTH_SHORT).show() }

    Column(Modifier.fillMaxSize().backdrop().imePadding()) {
        // ---- bar ----
        Column(Modifier.background(tone.paper)) {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniIcon(R.drawable.ic_back, "Back", tone.ink, onBack)
                Text("Phone to phone", fontWeight = FontWeight.SemiBold, fontSize = ts(15f), modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                if (state.running) StatusLight(
                    state.guests > 0,
                    when (state.guests) { 0 -> "Waiting"; 1 -> "Connected"; else -> "${state.guests} phones" },
                    Modifier.widthIn(max = 140.dp),
                )
                if (state.running) Box {
                    MiniIcon(R.drawable.ic_more, "Menu", tone.ink) { menu = true }
                    RetroMenu(menu, onDismiss = { menu = false }) {
                        MenuRow("Show code") { menu = false; showCode = true }
                        MenuRow("New code (disconnects)") { menu = false; host.newCode(); showCode = true }
                        MenuRow("Turn on hotspot") { menu = false; hotspot() }
                        MenuRow("Stop phone to phone") { menu = false; host.stop() }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(Rule).background(tone.line))
        }

        when {
            !state.running -> Intro(state.error, onStart = { app.scope.launch(Dispatchers.IO) { host.start() } }, onHotspot = ::hotspot, modifier = Modifier.weight(1f))
            showCode || (state.guests == 0 && items.isEmpty()) -> CodePanel(
                state, onHotspot = ::hotspot, onChoose = host::choose,
                onDone = if (items.isNotEmpty() || state.guests > 0) ({ showCode = false }) else null,
                modifier = Modifier.weight(1f),
            )
            else -> {
                if (state.guests == 0) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(tone.accentSoft).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("The other phone left. It reconnects by itself while on your hotspot.", fontSize = ts(12.5f), modifier = Modifier.weight(1f))
                    Text("CODE", color = tone.accent, fontWeight = FontWeight.SemiBold, fontSize = ts(12.5f),
                        modifier = Modifier.clickableNoRipple { showCode = true }.padding(start = 12.dp))
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (items.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Connected", fontWeight = FontWeight.SemiBold, fontSize = ts(17f))
                            Spacer(Modifier.height(6.dp))
                            Text("Send a note, photos, files or a whole folder. They land on the other phone right away.",
                                color = tone.muted, fontSize = ts(13f), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
                        }
                    }
                    items(items, key = { it.id }) { HostSlip(app, it) }
                }
            }
        }

        // ---- transfers ----
        transfers.forEach { t ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.label} ${t.name}", fontSize = ts(11.5f), color = tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (t.bps > 0) Text("%.1f MB/s".format(t.bps / 1e6), fontSize = ts(11.5f), color = tone.accent, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                ProgressBar(t.progress)
            }
        }

        // ---- composer ----
        if (state.running) Row(
            Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().hardShadow().clip(RectangleShape)
                .background(tone.paper).ruled(RectangleShape).padding(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconSquare(R.drawable.ic_plus, "Attach", onClick = { attachMenu = true })
                RetroMenu(attachMenu, onDismiss = { attachMenu = false }) {
                    MenuRow("Photos & videos") {
                        attachMenu = false
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    MenuRow("Files") { attachMenu = false; pickFiles.launch(arrayOf("*/*")) }
                    MenuRow("Folder as zip") { attachMenu = false; pickFolder.launch(null) }
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 150.dp).padding(horizontal = 10.dp, vertical = 11.dp),
                textStyle = TextStyle(fontFamily = tone.family, fontSize = ts(15f), lineHeight = ts(21f), color = tone.ink),
                cursorBrush = SolidColor(tone.accent),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Note to the other phone…", color = tone.muted, fontSize = ts(15f), maxLines = 1)
                    inner()
                },
            )
            IconSquare(R.drawable.ic_send, "Send", primary = true, enabled = draft.isNotBlank(), onClick = {
                host.sendText(draft)
                draft = ""
            })
        } else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun Intro(error: String?, onStart: () -> Unit, onHotspot: () -> Unit, modifier: Modifier) {
    val tone = LocalTone.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.Center) {
        RetroWindow(title = "Phone to phone", modifier = Modifier.fillMaxWidth()) {
            Text("Send to any phone. No laptop, no app on theirs.", fontSize = ts(20f), fontWeight = FontWeight.SemiBold, lineHeight = ts(26f))
            Spacer(Modifier.height(8.dp))
            Text(
                "This phone shows a code. The other phone (iPhone or Android) scans it with its camera and opens Tonefer in its browser. " +
                    "Everything goes straight between the two phones, encrypted, with no internet.",
                color = tone.muted,
            )
            Spacer(Modifier.height(16.dp))
            Steps(
                "Turn on this phone's hotspot",
                "Join that hotspot on the other phone",
                "Press Start and scan the code with the other phone's camera",
            )
            Spacer(Modifier.height(18.dp))
            RetroButton("Start", primary = true, icon = R.drawable.ic_qr, modifier = Modifier.fillMaxWidth(), onClick = onStart)
            Spacer(Modifier.height(10.dp))
            RetroButton("Turn on hotspot", icon = R.drawable.ic_link, modifier = Modifier.fillMaxWidth(), onClick = onHotspot)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = tone.danger, fontSize = ts(12.5f))
            }
            Spacer(Modifier.height(12.dp))
            Text("Already on the same Wi‑Fi? That works too, no hotspot needed.", color = tone.muted, fontSize = ts(12f))
        }
    }
}

@Composable
private fun CodePanel(
    state: PhoneHost.State,
    onHotspot: () -> Unit,
    onChoose: (String) -> Unit,
    onDone: (() -> Unit)?,
    modifier: Modifier,
) {
    val tone = LocalTone.current
    val url = state.url
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RetroWindow(title = "Scan with the other phone", modifier = Modifier.fillMaxWidth(), onClose = onDone, dot = tone.accent) {
            if (qr == null) {
                Text("Turn on your hotspot", fontWeight = FontWeight.SemiBold, fontSize = ts(17f))
                Spacer(Modifier.height(6.dp))
                Text("The code appears here as soon as this phone is sharing a hotspot or is on Wi‑Fi.", color = tone.muted, fontSize = ts(13f))
                Spacer(Modifier.height(14.dp))
                RetroButton("Turn on hotspot", primary = true, icon = R.drawable.ic_link, modifier = Modifier.fillMaxWidth(), onClick = onHotspot)
            } else {
                // Always dark on white: every camera reads that, in light and dark theme alike.
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(14.dp)) {
                    Image(qr.asImageBitmap(), "Pairing code", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
                Spacer(Modifier.height(14.dp))
                Steps(
                    "On the other phone, join this phone's hotspot",
                    "Open its camera and point it at the code",
                    "Tap the link that pops up. Done.",
                )
                Spacer(Modifier.height(10.dp))
                Label("Address")
                Spacer(Modifier.height(4.dp))
                Text("${state.address}:${state.port}", fontSize = ts(13f), fontWeight = FontWeight.Medium)
                if (state.addresses.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text("Not connecting? Try the other network:", color = tone.muted, fontSize = ts(12f))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.addresses.forEach { a ->
                            val on = a == state.address
                            Text(a, fontSize = ts(12f), color = if (on) tone.accentInk else tone.ink,
                                modifier = Modifier.background(if (on) tone.accent else tone.soft).clickableNoRipple { onChoose(a) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("The key is inside the code only. Anyone who scans it can send to you, so show it only to the phone you mean.",
                    color = tone.muted, fontSize = ts(11.5f))
            }
        }
    }
}

@Composable
private fun HostSlip(app: ToneferApp, item: Item) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val mine = !item.fromPhone
    val file = remember(item.id) { app.host.fileFor(item) }
    val time = remember(item.ts) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.ts)) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 310.dp)
                .background(if (mine) tone.accentSoft else tone.paper)
                .then(if (mine) Modifier else Modifier.border(Rule, tone.line))
                .padding(12.dp),
        ) {
            when {
                item.isText -> Text(item.text.orEmpty(), fontSize = ts(15f), lineHeight = ts(21f))
                item.isImage && file != null -> AsyncImage(file, item.name, Modifier.widthIn(max = 280.dp).heightIn(max = 320.dp)
                    .clickableNoRipple { Files.open(context, file, item.mime ?: "image/*") }, contentScale = ContentScale.Fit)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(tone.soft), contentAlignment = Alignment.Center) {
                        Text(if (item.files > 0) "ZIP" else item.extension, fontSize = ts(10.5f), fontWeight = FontWeight.SemiBold, color = tone.muted)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(item.name.orEmpty(), fontWeight = FontWeight.Medium, fontSize = ts(14f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Files.humanSize(item.size) + if (item.files > 0) " · ${item.files} files" else "", color = tone.muted, fontSize = ts(12f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(if (mine) "You · $time" else "Phone · $time", color = tone.muted)
                Spacer(Modifier.weight(1f))
                if (item.isText) Action("COPY") {
                    Files.copyText(context, item.text.orEmpty())
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                if (!mine && file != null) {
                    Action("OPEN") {
                        try { Files.open(context, file, item.mime ?: "*/*") } catch (_: Exception) {
                            Toast.makeText(context, "No app here opens this file", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Action("SAVE") {
                        app.scope.launch {
                            val msg = try {
                                "Saved to " + withContext(Dispatchers.IO) { Files.saveToDevice(context, file, item.name ?: file.name, item.mime ?: "application/octet-stream") }
                            } catch (e: Exception) { "Couldn't save: ${e.message}" }
                            withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                if (mine && !item.isText) Action("REMOVE") { app.host.delete(item.id) }
            }
        }
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    val tone = LocalTone.current
    Text(text, color = tone.accent, fontWeight = FontWeight.SemiBold, fontSize = ts(11.5f),
        modifier = Modifier.clickableNoRipple(onClick).padding(start = 14.dp, top = 4.dp, bottom = 4.dp))
}

private fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val w = m.width
    val h = m.height
    val px = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF111111.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
