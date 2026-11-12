package com.pankqx.tonefer.gallery

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.ui.components.Label
import com.pankqx.tonefer.ui.components.RetroButton
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import com.pankqx.tonefer.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Show(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun dayOf(t: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(t: Long): String {
    val today = dayOf(System.currentTimeMillis())
    val yesterday = dayOf(System.currentTimeMillis() - 86_400_000L)
    val sameYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)
    return when (dayOf(t)) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> SimpleDateFormat(if (sameYear) "EEE, d MMMM" else "EEE, d MMMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

/** The phone's photos and videos, newest first, with an instant viewer. Everything stays on the phone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(app: ToneferApp, onBack: () -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.any { it } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(mediaPermissions()) }

    var all by remember { mutableStateOf<List<MediaItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted, reload) { if (granted) all = withContext(Dispatchers.IO) { Media.load(context) } }

    var show by rememberSaveable { mutableStateOf(Show.ALL) }
    val items = remember(all, show) {
        when (show) { Show.ALL -> all.orEmpty(); Show.PHOTOS -> all.orEmpty().filter { !it.video }; Show.VIDEOS -> all.orEmpty().filter { it.video } }
    }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var picked by remember { mutableStateOf(setOf<Long>()) }

    // ---- delete: Android shows its own confirmation, and on Android 11+ items go to the trash ----
    var pendingDelete by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    fun afterDelete(ok: Boolean) {
        if (ok) {
            val gone = pendingDelete.map { it.id }.toSet()
            gone.forEach(Media::forget)
            all = all?.filterNot { it.id in gone }
            picked = picked - gone
            Toast.makeText(context, if (Build.VERSION.SDK_INT >= 30) "Moved to trash. It stays there for 30 days." else "Deleted", Toast.LENGTH_SHORT).show()
        }
        pendingDelete = emptyList()
    }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val approved = r.resultCode == Activity.RESULT_OK
        if (approved && Build.VERSION.SDK_INT < 30) {
            // Android 10: the approval only unlocks the file; delete it now.
            pendingDelete.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
        }
        afterDelete(approved)
    }
    fun delete(list: List<MediaItem>) {
        if (list.isEmpty()) return
        pendingDelete = list
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createTrashRequest(context.contentResolver, list.map { it.uri }, true)
                confirm.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                try {
                    list.forEach { context.contentResolver.delete(it.uri, null, null) }
                    afterDelete(true)
                } catch (e: RecoverableSecurityException) {
                    confirm.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
            }
        } catch (e: Exception) {
            pendingDelete = emptyList()
            Toast.makeText(context, "Couldn't delete: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    fun send(list: List<MediaItem>) {
        if (list.isEmpty()) return
        val outgoing = list.map { app.client.outgoingFrom(it.uri) }
        Toast.makeText(context, "Sending ${list.size} to your laptop", Toast.LENGTH_SHORT).show()
        app.scope.launch { app.client.sendFiles(outgoing) }
    }

    BackHandler {
        when {
            viewing != null -> viewing = null
            picked.isNotEmpty() -> picked = emptySet()
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(tone.desk)) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar ----
            Column(Modifier.background(tone.paper)) {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = ts(26f), modifier = Modifier.combinedClickable(onClick = onBack).padding(end = 14.dp))
                    Text(if (picked.isEmpty()) "Gallery" else "${picked.size} selected", fontWeight = FontWeight.SemiBold, fontSize = ts(17f), modifier = Modifier.weight(1f))
                    if (picked.isEmpty()) Text("${items.size}", color = tone.muted, fontSize = ts(12.5f))
                    else Text("Cancel", color = tone.accent, fontSize = ts(13f), modifier = Modifier.combinedClickable(onClick = { picked = emptySet() }))
                }
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Show.values().forEach { s ->
                        val on = s == show
                        Text(
                            s.label, fontSize = ts(12.5f), color = if (on) tone.paper else tone.ink,
                            modifier = Modifier.background(if (on) tone.ink else tone.soft).combinedClickable(onClick = { show = s; picked = emptySet() })
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            when {
                !granted -> Empty("Allow access to see your photos",
                    "Tonefer only shows them here. Nothing is uploaded anywhere.", "Allow") { ask.launch(mediaPermissions()) }
                all == null -> Empty("Loading…", "", null) {}
                items.isEmpty() -> Empty("Nothing here yet", "Photos and videos on this phone appear here, newest first.", null) {}
                else -> {
                    // Build the day headers once per list.
                    val rows = remember(items) {
                        val out = ArrayList<Any>(items.size + 64)
                        var last = -1
                        items.forEachIndexed { i, it ->
                            val d = dayOf(it.time)
                            if (d != last) { out.add(dayLabel(it.time)); last = d }
                            out.add(i)
                        }
                        out
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(rows.size, key = { i -> rows[i].let { r -> if (r is Int) items[r].id else "h$i" } },
                            span = { i -> if (rows[i] is String) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                            contentType = { i -> if (rows[i] is String) 0 else 1 }) { i ->
                            val r = rows[i]
                            if (r is String) {
                                Label(r, Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp))
                            } else {
                                val index = r as Int
                                val item = items[index]
                                val sel = item.id in picked
                                Box(
                                    Modifier.aspectRatio(1f).background(tone.soft).combinedClickable(
                                        onClick = { if (picked.isEmpty()) viewing = index else picked = if (sel) picked - item.id else picked + item.id },
                                        onLongClick = { picked = picked + item.id },
                                    ),
                                ) {
                                    Thumb(item, Modifier.fillMaxSize().graphicsLayer { val s = if (sel) 0.86f else 1f; scaleX = s; scaleY = s })
                                    if (item.video) Text(
                                        "▶ " + Media.duration(item.durationMs), color = Color.White, fontSize = ts(10f),
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                    if (sel) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).background(tone.accent), contentAlignment = Alignment.Center) {
                                        Text("✓", color = Color.White, fontSize = ts(12f), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (picked.isNotEmpty()) Row(
                Modifier.fillMaxWidth().background(tone.paper).navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val chosen = items.filter { it.id in picked }
                RetroButton("Send to laptop", onClick = { send(chosen); picked = emptySet() }, modifier = Modifier.weight(1f), primary = true)
                RetroButton("Delete", onClick = { delete(chosen) }, modifier = Modifier.weight(1f), danger = true)
            }
        }

        viewing?.let { start ->
            Viewer(
                items = items, start = start.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                onClose = { viewing = null }, onSend = { send(listOf(it)) }, onDelete = { delete(listOf(it)) },
            )
        }
    }
}

@Composable
private fun Empty(title: String, text: String, action: String?, onAction: () -> Unit) {
    val tone = LocalTone.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = ts(16f))
        if (text.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(text, color = tone.muted, fontSize = ts(13f)) }
        if (action != null) { Spacer(Modifier.height(16.dp)); RetroButton(action, onClick = onAction, primary = true) }
    }
}

/** A grid thumbnail. Uses the in-memory copy at once when there is one, so scrolling back never flickers. */
@Composable
private fun Thumb(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState(initialValue = Media.cached(item.id), item.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Media.thumbnail(context, item) }
    }
    bmp?.let { Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** Full-screen viewer: swipe sideways to move, double-tap to zoom, videos start as soon as they are on screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(items: List<MediaItem>, start: Int, onClose: () -> Unit, onSend: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = start) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    val current = items.getOrNull(pager.currentPage.coerceIn(0, items.size - 1)) ?: return

    Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0A))) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { items.getOrNull(it)?.id ?: it }) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.video) VideoPage(item, active = pager.settledPage == page, onTap = { chrome = !chrome })
            else PhotoPage(item, onZoom = { zoomed = it }, onTap = { chrome = !chrome })
        }
        if (chrome) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xB3000000)).statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", color = Color.White, fontSize = ts(26f), modifier = Modifier.combinedClickable(onClick = onClose).padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, fontSize = ts(13.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(current.time)) + " · " + Files.humanSize(current.size) +
                            if (current.folder.isNotEmpty()) " · ${current.folder}" else "",
                        color = Color(0xFFA8A199), fontSize = ts(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf("Send to laptop" to { onSend(current) }, "Delete" to { onDelete(current) }).forEach { (label, act) ->
                    Text(label, color = Color.White, fontSize = ts(13.5f), modifier = Modifier.combinedClickable(onClick = act).padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(item: MediaItem, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    DisposableEffect(item.id) { onDispose { onZoom(false) } }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.6f
                        onZoom(scale > 1f)
                    },
                )
            }
            .pointerInput(item.id, scale > 1f) {
                // Panning and pinching only while zoomed, so a plain swipe still changes the page.
                if (scale > 1f) detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    val maxX = size.width * (scale - 1) / 2
                    val maxY = size.height * (scale - 1) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero; onZoom(false) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The small thumbnail is already in memory: show it at once, then the full picture on top.
        Thumb(item, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        AsyncImage(
            model = item.uri, contentDescription = item.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    var ready by remember(item.id) { mutableStateOf(false) }
    var holder by remember(item.id) { mutableStateOf<VideoView?>(null) }
    // Play only the page that is on screen; neighbours stay prepared and paused.
    LaunchedEffect(active, ready) {
        val v = holder ?: return@LaunchedEffect
        if (active && ready) v.start() else if (v.isPlaying) v.pause()
    }
    DisposableEffect(item.id) { onDispose { holder?.stopPlayback() } }
    Box(Modifier.fillMaxSize().pointerInput(item.id) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setOnPreparedListener { mp -> mp.isLooping = true; ready = true }
                    setOnErrorListener { _, _, _ -> true }
                    setVideoURI(item.uri)
                    holder = this
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!ready) Thumb(item, Modifier.fillMaxSize())
    }
}
