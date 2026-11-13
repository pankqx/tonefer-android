package com.pankqx.tonefer.gallery

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.VerticalPager
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.ui.components.Label
import com.pankqx.tonefer.ui.components.RetroButton
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import com.pankqx.tonefer.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Show(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), FILES("Files") }

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

/** Day headers between items: a String for a header, an Int index for an item. */
private fun <T> withDays(list: List<T>, time: (T) -> Long): List<Any> {
    val out = ArrayList<Any>(list.size + 64)
    var last = -1
    list.forEachIndexed { i, it ->
        val d = dayOf(time(it))
        if (d != last) { out.add(dayLabel(time(it))); last = d }
        out.add(i)
    }
    return out
}

/** The phone's photos, videos and files, newest first, with a reels-style viewer. Everything stays on the phone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(app: ToneferApp, onBack: () -> Unit) {
    val tone = LocalTone.current
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.any { it } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(mediaPermissions()) }

    var filesAllowed by remember { mutableStateOf(FilesIndex.allowed(context)) }
    // Coming back from Android's "All files access" screen: check again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) filesAllowed = FilesIndex.allowed(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    var reload by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<MediaItem>?>(null) }
    LaunchedEffect(granted, reload) { if (granted) all = withContext(Dispatchers.IO) { Media.load(context) } }
    var files by remember { mutableStateOf<List<FileItem>?>(null) }

    var show by rememberSaveable { mutableStateOf(Show.ALL) }
    LaunchedEffect(show, filesAllowed, reload) {
        if (show == Show.FILES && filesAllowed) files = withContext(Dispatchers.IO) { FilesIndex.load(context) }
    }
    val items = remember(all, show) {
        when (show) {
            Show.ALL -> all.orEmpty()
            Show.PHOTOS -> all.orEmpty().filter { !it.video }
            Show.VIDEOS -> all.orEmpty().filter { it.video }
            Show.FILES -> emptyList()
        }
    }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var picked by remember { mutableStateOf(setOf<Long>()) }

    // ---- delete: Android asks for confirmation; on Android 11+ items go to the system trash ----
    var pending by remember { mutableStateOf<List<Pair<Long, Uri>>>(emptyList()) }
    var confirmPermanent by remember { mutableStateOf<List<Pair<Long, Uri>>>(emptyList()) }
    fun afterDelete(ok: Boolean, trashed: Boolean) {
        if (ok) {
            val gone = pending.map { it.first }.toSet()
            gone.forEach(Media::forget)
            all = all?.filterNot { it.id in gone }
            files = files?.filterNot { it.id in gone }
            picked = picked - gone
            Toast.makeText(context, if (trashed) "Moved to trash. It stays there for 30 days." else "Deleted", Toast.LENGTH_SHORT).show()
        }
        pending = emptyList()
    }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val approved = r.resultCode == Activity.RESULT_OK
        if (approved && Build.VERSION.SDK_INT < 30) pending.forEach { runCatching { context.contentResolver.delete(it.second, null, null) } }
        afterDelete(approved, Build.VERSION.SDK_INT >= 30)
    }
    fun delete(list: List<Pair<Long, Uri>>) {
        if (list.isEmpty()) return
        pending = list
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createTrashRequest(context.contentResolver, list.map { it.second }, true)
                confirm.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                try {
                    list.forEach { context.contentResolver.delete(it.second, null, null) }
                    afterDelete(true, false)
                } catch (e: RecoverableSecurityException) {
                    confirm.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
            }
        } catch (e: Exception) {
            // Some files (not photos or videos) can't go to the trash: ask before deleting for good.
            confirmPermanent = list
        }
    }
    fun send(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val outgoing = uris.map { app.client.outgoingFrom(it) }
        Toast.makeText(context, "Sending ${uris.size} to your laptop", Toast.LENGTH_SHORT).show()
        app.scope.launch { app.client.sendFiles(outgoing) }
    }
    fun open(f: FileItem) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, "No app on this phone opens ${f.name.substringAfterLast('.').uppercase()} files", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        when {
            confirmPermanent.isNotEmpty() -> confirmPermanent = emptyList()
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
                    if (picked.isEmpty()) Text("${if (show == Show.FILES) files.orEmpty().size else items.size}", color = tone.muted, fontSize = ts(12.5f))
                    else Text("Cancel", color = tone.accent, fontSize = ts(13f), modifier = Modifier.combinedClickable(onClick = { picked = emptySet() }))
                }
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Show.values().forEach { s ->
                        val on = s == show
                        Text(
                            s.label, fontSize = ts(12.5f), color = if (on) tone.paper else tone.ink,
                            modifier = Modifier.background(if (on) tone.ink else tone.soft).combinedClickable(onClick = { show = s; picked = emptySet() })
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            when {
                show == Show.FILES && !filesAllowed -> Empty(
                    "Allow access to your files",
                    "To list documents, archives, music and APKs, Android needs \"All files access\" for Tonefer. Nothing leaves the phone unless you send it.",
                    "Open settings",
                ) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(own) }.onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        }
                    } else ask.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
                show == Show.FILES -> FileList(files, picked, onToggle = { id -> picked = if (id in picked) picked - id else picked + id }, onOpen = ::open)
                !granted -> Empty("Allow access to see your photos",
                    "Tonefer only shows them here. Nothing is uploaded anywhere.", "Allow") { ask.launch(mediaPermissions()) }
                all == null -> Empty("Loading…", "", null) {}
                items.isEmpty() -> Empty("Nothing here yet", "Photos and videos on this phone appear here, newest first.", null) {}
                else -> {
                    val rows = remember(items) { withDays(items) { it.time } }
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
                                    if (sel) Check(Modifier.align(Alignment.TopStart).padding(6.dp))
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
                val chosen: List<Pair<Long, Uri>> = if (show == Show.FILES) files.orEmpty().filter { it.id in picked }.map { it.id to it.uri }
                else items.filter { it.id in picked }.map { it.id to it.uri }
                RetroButton("Send to laptop", onClick = { send(chosen.map { it.second }); picked = emptySet() }, modifier = Modifier.weight(1f), primary = true)
                RetroButton("Delete", onClick = { delete(chosen) }, modifier = Modifier.weight(1f), danger = true)
            }
        }

        viewing?.let { start ->
            if (items.isEmpty()) viewing = null
            else Reels(
                items = items, start = start.coerceIn(0, items.size - 1),
                onClose = { viewing = null }, onSend = { send(listOf(it.uri)) }, onDelete = { delete(listOf(it.id to it.uri)) },
            )
        }

        if (confirmPermanent.isNotEmpty()) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(28.dp).background(tone.paper).border(1.dp, tone.line).padding(20.dp)) {
                Text("Delete for good?", fontWeight = FontWeight.SemiBold, fontSize = ts(16f))
                Spacer(Modifier.height(8.dp))
                Text("Android can't move ${if (confirmPermanent.size == 1) "this file" else "these ${confirmPermanent.size} files"} to the trash, so this can't be undone.",
                    color = tone.muted, fontSize = ts(13f))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RetroButton("Cancel", onClick = { confirmPermanent = emptyList(); pending = emptyList() }, modifier = Modifier.weight(1f))
                    RetroButton("Delete", danger = true, modifier = Modifier.weight(1f), onClick = {
                        val list = confirmPermanent
                        confirmPermanent = emptyList()
                        pending = list
                        val ok = list.all { runCatching { context.contentResolver.delete(it.second, null, null) > 0 }.getOrDefault(false) }
                        afterDelete(ok, false)
                        if (!ok) Toast.makeText(context, "Some files couldn't be deleted", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

@Composable
private fun Check(modifier: Modifier = Modifier) {
    val tone = LocalTone.current
    Box(modifier.size(20.dp).background(tone.accent), contentAlignment = Alignment.Center) {
        Text("✓", color = Color.White, fontSize = ts(12f), fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(files: List<FileItem>?, picked: Set<Long>, onToggle: (Long) -> Unit, onOpen: (FileItem) -> Unit) {
    val tone = LocalTone.current
    when {
        files == null -> Empty("Loading…", "", null) {}
        files.isEmpty() -> Empty("No files found", "Documents, archives, music and APKs on this phone appear here.", null) {}
        else -> {
            val rows = remember(files) { withDays(files) { it.time } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(rows.size, key = { i -> rows[i].let { r -> if (r is Int) files[r].id else "h$i" } }) { i ->
                    val r = rows[i]
                    if (r is String) Label(r, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                    else {
                        val f = files[r as Int]
                        val sel = f.id in picked
                        Row(
                            Modifier.fillMaxWidth().background(if (sel) tone.accentSoft else tone.desk)
                                .combinedClickable(onClick = { if (picked.isEmpty()) onOpen(f) else onToggle(f.id) }, onLongClick = { onToggle(f.id) })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(46.dp).border(1.dp, tone.line).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text(f.name.substringAfterLast('.', "file").take(4).uppercase(), fontSize = ts(10f), color = tone.muted, textAlign = TextAlign.Center)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(f.name, fontSize = ts(13.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(f.folder, Files.humanSize(f.size)).filter { it.isNotEmpty() }.joinToString(" · "),
                                    color = tone.muted, fontSize = ts(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (sel) Check()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, text: String, action: String?, onAction: () -> Unit) {
    val tone = LocalTone.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = ts(16f), textAlign = TextAlign.Center)
        if (text.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(text, color = tone.muted, fontSize = ts(13f), textAlign = TextAlign.Center) }
        if (action != null) { Spacer(Modifier.height(16.dp)); RetroButton(action, onClick = onAction, primary = true) }
    }
}

/** A thumbnail. Uses the in-memory copy at once when there is one, so scrolling back never flickers. */
@Composable
private fun Thumb(item: MediaItem, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bmp by produceState(initialValue = Media.cached(item.id), item.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Media.thumbnail(context, item) }
    }
    bmp?.let { Image(it.asImageBitmap(), contentDescription = item.name, contentScale = scale, modifier = modifier) }
}

/**
 * Reels-style viewer: swipe up for the next photo or video, down for the previous. The pages on
 * either side are prepared while you watch, so the next video starts the moment it slides in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Reels(items: List<MediaItem>, start: Int, onClose: () -> Unit, onSend: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    val current = items.getOrNull(pager.currentPage.coerceIn(0, items.size - 1)) ?: return

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1,
            key = { items.getOrNull(it)?.id ?: it }, modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@VerticalPager
            if (item.video) ReelVideo(item, active = pager.settledPage == page, onTap = { chrome = !chrome })
            else PhotoPage(item, onZoom = { zoomed = it }, onTap = { chrome = !chrome })
        }

        if (chrome) {
            // top: back, name, date
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", color = Color.White, fontSize = ts(26f), modifier = Modifier.combinedClickable(onClick = onClose).padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, fontSize = ts(13.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(current.time)) + " · " + Files.humanSize(current.size) +
                            if (current.folder.isNotEmpty()) " · ${current.folder}" else "",
                        color = Color(0xFFD0CAC2), fontSize = ts(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("${pager.currentPage + 1}/${items.size}", color = Color(0xFFD0CAC2), fontSize = ts(11.5f))
            }
            // right side actions, like reels
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 10.dp, bottom = 34.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ReelAction("⇪", "Send") { onSend(current) }
                ReelAction("✕", "Delete") { onDelete(current) }
            }
        }
    }
}

@Composable
private fun ReelAction(glyph: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(46.dp).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            Text(glyph, color = Color.White, fontSize = ts(20f))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White, fontSize = ts(11f))
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
                // Pan and pinch only while zoomed, so a plain swipe still moves to the next item.
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
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        // The thumbnail is already in memory: it shows at once, the full picture lands on top.
        Thumb(item, layer, ContentScale.Fit)
        AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = layer)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ReelVideo(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    // Each page gets its own player, prepared as soon as the page exists (one before and one
    // after the current page), so swiping to a video plays it without a wait.
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    var firstFrame by remember(item.id) { mutableStateOf(false) }
    var paused by remember(item.id) { mutableStateOf(false) }
    var progress by remember(item.id) { mutableFloatStateOf(0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onRenderedFirstFrame() { firstFrame = true } }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(active) {
        if (!active) { player.pause(); player.seekTo(0); paused = false }
    }
    LaunchedEffect(active, paused) { player.playWhenReady = active && !paused }
    LaunchedEffect(active) {
        while (active) {
            val d = player.duration
            if (d > 0) progress = (player.currentPosition.toFloat() / d).coerceIn(0f, 1f)
            delay(120)
        }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(item.id) { detectTapGestures(onTap = { paused = !paused; onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrame) Thumb(item, Modifier.fillMaxSize(), ContentScale.Fit)
        if (paused) Box(Modifier.size(72.dp).background(Color(0x80000000)), contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, fontSize = ts(30f))
        }
        Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
        }
    }
}
