package com.pankqx.tonefer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.pankqx.tonefer.R
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.net.Conn
import com.pankqx.tonefer.ui.components.Brand
import com.pankqx.tonefer.ui.components.IconSquare
import com.pankqx.tonefer.ui.components.Label
import com.pankqx.tonefer.ui.components.MiniIcon
import com.pankqx.tonefer.ui.components.RetroButton
import com.pankqx.tonefer.ui.components.RetroWindow
import com.pankqx.tonefer.ui.components.Rule
import com.pankqx.tonefer.ui.components.StatusLight
import com.pankqx.tonefer.ui.components.backdrop
import com.pankqx.tonefer.ui.components.clickableNoRipple
import com.pankqx.tonefer.ui.components.DashedDivider
import com.pankqx.tonefer.ui.components.hardShadow
import com.pankqx.tonefer.ui.components.ruled
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import com.pankqx.tonefer.util.Files
import com.pankqx.tonefer.util.Hotspot
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun HomeScreen(app: ToneferApp, onSettings: () -> Unit, onHelp: () -> Unit, onAbout: () -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by app.client.conn.collectAsState()
    val items by app.client.items.collectAsState()
    val uploads by app.client.uploads.collectAsState()
    val queued by app.client.queued.collectAsState()
    val speed by app.client.speed.collectAsState()
    LaunchedEffect(speed) {
        speed?.let { Toast.makeText(context, "Speed test: ↓ %.1f MB/s · ↑ %.1f MB/s".format(it.down / 1e6, it.up / 1e6), Toast.LENGTH_LONG).show() }
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Item?>(null) }
    var menu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // Ask once for notification permission (Android 13+).
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && Build.FINGERPRINT != "robolectric" &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val entries = remember(items) { withDays(items) }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.animateScrollToItem(entries.lastIndex) }

    fun sendUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val outgoing = uris.map { app.client.outgoingFrom(it) }
        app.scope.launch {
            val ok = app.client.sendFiles(outgoing)
            scope.launch {
                if (ok < outgoing.size) Toast.makeText(context, if (ok == 0) "Couldn't reach your laptop" else "Sent $ok of ${outgoing.size}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { sendUris(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sendUris(it) }

    Column(Modifier.fillMaxSize().backdrop().imePadding()) {
        // ---- menu bar ----
        Column(Modifier.background(tone.paper)) {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand()
                Spacer(Modifier.weight(1f))
                StatusLight(conn is Conn.Online, statusLabel(conn), Modifier.widthIn(max = 170.dp))
                Spacer(Modifier.width(4.dp))
                Box {
                    MiniIcon(R.drawable.ic_more, "Menu", tone.ink) { menu = true }
                    RetroMenu(menu, onDismiss = { menu = false }) {
                        MenuRow("Settings") { menu = false; onSettings() }
                        MenuRow("Instructions") { menu = false; onHelp() }
                        MenuRow("Find laptop again") { menu = false; app.client.reconnect() }
                        MenuRow("About Tonefer") { menu = false; onAbout() }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(Rule).background(tone.line))
        }

        // ---- connection banner ----
        when (val c = conn) {
            is Conn.Offline, Conn.Searching -> Banner(
                title = if (c == Conn.Searching) "Looking for your laptop…" else "Can't reach your laptop",
                text = (if (queued > 0) "$queued waiting. They'll send by themselves once connected. " else "") +
                    "Turn on your hotspot (or USB tethering). The laptop joins it automatically after the one-click setup.",
                onHotspot = { if (!Hotspot.open(context)) Toast.makeText(context, "Open Settings → Hotspot", Toast.LENGTH_SHORT).show() },
                onRetry = if (c == Conn.Searching) null else ({ app.client.reconnect() }),
            )
            else -> if (queued > 0) Banner(
                title = "Sending $queued queued item${if (queued == 1) "" else "s"}…",
                text = "Delivering what you shared while the laptop was away.",
                onHotspot = null, onRetry = null,
            )
        }

        // ---- timeline ----
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) item {
                EmptyState(conn is Conn.Online)
            }
            items(entries, key = { it.key }) { e ->
                when (e) {
                    is Entry.Day -> DayRule(e.label)
                    is Entry.Msg -> Slip(app, e.item, onOpenImage = { viewing = it })
                }
            }
        }

        // ---- uploads in flight ----
        uploads.forEach { u ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sending ${u.name}", fontSize = ts(11.5f), color = tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (u.bps > 0) Text("%.1f MB/s".format(u.bps / 1e6), fontSize = ts(11.5f), color = tone.accent, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                ProgressBar(u.progress)
            }
        }

        // ---- composer ----
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .hardShadow()
                .clip(RectangleShape)
                .background(tone.paper)
                .ruled(RectangleShape)
                .padding(6.dp),
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
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 150.dp).padding(horizontal = 10.dp, vertical = 11.dp),
                textStyle = TextStyle(fontFamily = tone.family, fontSize = ts(15f), lineHeight = ts(21f), color = tone.ink),
                cursorBrush = SolidColor(tone.accent),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Note to laptop…", color = tone.muted, fontSize = ts(15f))
                    inner()
                },
            )
            IconSquare(
                R.drawable.ic_send, "Send", primary = true, enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft
                    draft = ""
                    app.scope.launch {
                        if (!app.client.sendText(text)) scope.launch {
                            draft = text
                            Toast.makeText(context, "Couldn't reach your laptop", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }

    viewing?.let { item -> ImageViewer(app, item) { viewing = null } }
}

private fun statusLabel(c: Conn) = when (c) {
    is Conn.Online -> c.laptop
    is Conn.Connecting -> "Connecting"
    Conn.Searching -> "Searching"
    is Conn.Offline -> "Offline"
    Conn.Unpaired -> "Unpaired"
    Conn.Idle -> "Idle"
}

// ---------------------------------------------------------------- timeline entries

private sealed interface Entry {
    val key: String
    data class Day(val label: String) : Entry { override val key = "day:$label" }
    data class Msg(val item: Item) : Entry { override val key = item.id }
}

private fun withDays(items: List<Item>): List<Entry> {
    val out = ArrayList<Entry>(items.size + 4)
    var last = ""
    for (it in items) {
        val label = dayLabel(it.ts)
        if (label != last) { out += Entry.Day(label); last = label }
        out += Entry.Msg(it)
    }
    return out
}

private fun dayLabel(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val now = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, now)) return "Today"
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, now)) return "Yesterday"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
}

@Composable
private fun DayRule(label: String) {
    val tone = LocalTone.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        DottedLine(Modifier.weight(1f))
        Label(label, Modifier.padding(horizontal = 12.dp), tone.muted)
        DottedLine(Modifier.weight(1f))
    }
}

@Composable
private fun DottedLine(modifier: Modifier) {
    val c = LocalTone.current.ink.copy(alpha = .4f)
    Box(modifier.height(2.dp).drawBehind {
        var x = 0f
        val step = 4.dp.toPx()
        while (x < size.width) { drawCircle(c, 0.9.dp.toPx(), Offset(x, size.height / 2)); x += step }
    })
}

@Composable
private fun EmptyState(online: Boolean) {
    val tone = LocalTone.current
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RectangleShape).background(tone.paper).ruled().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (online) "No transfers yet" else "Waiting for laptop", fontWeight = FontWeight.SemiBold, fontSize = ts(16f))
            Spacer(Modifier.height(8.dp))
            Text(
                if (online) "Type a note below, tap + for photos, or use Share → Tonefer from any app."
                else "Open Tonefer on your laptop. This screen fills in as soon as it connects.",
                color = tone.muted, fontSize = ts(13f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- slips

@Composable
private fun Slip(app: ToneferApp, item: Item, onOpenImage: (Item) -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine = item.fromPhone
    val bg = if (mine) tone.mine else tone.soft
    val fg = if (mine) tone.mineInk else tone.ink
    val sub = if (mine) tone.mineMuted else tone.muted

    fun withFile(action: suspend (File) -> Unit) {
        scope.launch {
            try { action(app.client.fileFor(item)) }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_SHORT).show() }
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
      Box(Modifier.fillMaxWidth(.88f), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .then(if (item.isText) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth())
                .hardShadow(2.dp)
                .clip(bubbleShape(mine, tone.classic))
                .background(bg)
                .then(if (tone.classic) Modifier.ruled(androidx.compose.ui.graphics.RectangleShape) else Modifier)
                .padding(start = if (item.isImage) 5.dp else 14.dp, end = if (item.isImage) 5.dp else 14.dp,
                    top = if (item.isImage) 5.dp else 12.dp, bottom = 4.dp),
        ) {
            when {
                item.isText -> Text(linkified(item.text.orEmpty()), color = fg, fontSize = ts(14.5f), lineHeight = ts(21f))
                item.isImage -> {
                    val file by produceState<File?>(null, item.id) { value = runCatching { app.client.fileFor(item) }.getOrNull() }
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp)
                            .clip(RectangleShape)
                            .then(if (file == null) Modifier.dither(tone.paper, tone.soft) else Modifier)
                    ) {
                        if (file != null) AsyncImage(
                            model = file, contentDescription = item.name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp)
                                .clickableNoRipple { onOpenImage(item) },
                        )
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableNoRipple {
                    withFile { Files.open(context, it, item.mime ?: "*/*") }
                }) {
                    Box(Modifier.size(44.dp).background(if (mine) Color.White.copy(alpha = .16f) else tone.paper),
                        contentAlignment = Alignment.Center) {
                        Text(item.extension, color = if (mine) fg else tone.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name.orEmpty(), color = fg, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Files.humanSize(item.size), color = sub, fontSize = ts(11.5f))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = if (item.isImage) 6.dp else 0.dp)) {
                val who = if (mine) "You" else "Laptop"
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.ts))
                val extra = if (item.kind == "file") " · ${Files.humanSize(item.size)}" else ""
                Text("${who.uppercase()} · $time$extra", color = sub, fontSize = ts(10.5f), letterSpacing = .6.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (item.isText) {
                    MiniIcon(R.drawable.ic_copy, "Copy", sub) {
                        Files.copyText(context, item.text.orEmpty())
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    MiniIcon(R.drawable.ic_download, "Save to phone", sub) {
                        withFile {
                            val folder = withContext(Dispatchers.IO) { Files.saveToDevice(context, it, item.name ?: it.name, item.mime ?: "application/octet-stream") }
                            Toast.makeText(context, "Saved to $folder", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MiniIcon(R.drawable.ic_link, "Share", sub) { withFile { Files.share(context, it, item.mime ?: "*/*") } }
                }
            }
        }
      }
    }
}

private fun Modifier.border1(color: Color) = this.then(Modifier.drawBehind {
    val w = 1.5.dp.toPx()
    drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
})

private fun Modifier.dither(a: Color, b: Color) = this.clipToBounds().background(a).drawBehind {
    val step = 6.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(b, Offset(x, size.height), Offset(x + size.height, 0f), 2.dp.toPx())
        x += step
    }
}

private fun bubbleShape(mine: Boolean, classic: Boolean) = when {
    classic -> androidx.compose.ui.graphics.RectangleShape
    else -> androidx.compose.ui.graphics.RectangleShape
}

private fun linkified(text: String) = buildAnnotatedString {
    val rx = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""")
    var last = 0
    for (m in rx.findAll(text)) {
        append(text.substring(last, m.range.first))
        withLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

// ---------------------------------------------------------------- bits

@Composable
fun ProgressBar(progress: Float) {
    val tone = LocalTone.current
    Box(Modifier.fillMaxWidth().height(6.dp).background(tone.soft)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).background(tone.accent))
    }
}

@Composable
private fun Banner(title: String, text: String, onHotspot: (() -> Unit)?, onRetry: (() -> Unit)?) {
    val tone = LocalTone.current
    val shape = RectangleShape
    Column(
        Modifier.padding(12.dp).fillMaxWidth().hardShadow(2.dp).clip(shape).background(tone.paper)
            .border(Rule, tone.danger.copy(alpha = .6f), shape).padding(14.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(text, color = tone.muted, fontSize = ts(12.5f), lineHeight = ts(18f))
        if (onHotspot != null || onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onHotspot != null) RetroButton("Turn on hotspot", primary = true, onClick = onHotspot)
                if (onRetry != null) RetroButton("Retry", icon = R.drawable.ic_refresh, onClick = onRetry)
            }
        }
    }
}

@Composable
fun RetroMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val tone = LocalTone.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = tone.paper,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(Rule, if (tone.classic) tone.ink else tone.line),
    ) { content() }
}

@Composable
fun MenuRow(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = ts(14f)) }, onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp))
}

@Composable
private fun ImageViewer(app: ToneferApp, item: Item, onClose: () -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file by produceState<File?>(null, item.id) { value = runCatching { app.client.fileFor(item) }.getOrNull() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(tone.desk.copy(alpha = .96f)).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
        ) {
            RetroWindow(item.name ?: "Photo", onClose = onClose, contentPadding = 8.dp, modifier = Modifier.fillMaxWidth()) {
                if (file != null) AsyncImage(model = file, contentDescription = item.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp))
                else Box(Modifier.fillMaxWidth().height(240.dp).dither(tone.soft, tone.paper))
                Spacer(Modifier.height(10.dp))
                Row {
                    RetroButton("Save", primary = true, icon = R.drawable.ic_download, modifier = Modifier.weight(1f), onClick = {
                        val f = file ?: return@RetroButton
                        scope.launch {
                            try {
                                val folder = withContext(Dispatchers.IO) { Files.saveToDevice(context, f, item.name ?: f.name, item.mime ?: "image/jpeg") }
                                Toast.makeText(context, "Saved to $folder", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { Toast.makeText(context, "Couldn't save", Toast.LENGTH_SHORT).show() }
                        }
                    })
                    Spacer(Modifier.width(10.dp))
                    RetroButton("Share", icon = R.drawable.ic_link, modifier = Modifier.weight(1f), onClick = {
                        file?.let { Files.share(context, it, item.mime ?: "image/*") }
                    })
                }
            }
        }
    }
}
