package com.pankqx.tonefer.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.pankqx.tonefer.crypto.Box
import com.pankqx.tonefer.crypto.Chunks
import com.pankqx.tonefer.data.ImageQuality
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.data.Pairing
import com.pankqx.tonefer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Connection state shown in the UI and the background notification. */
sealed interface Conn {
    data object Idle : Conn
    data class Connecting(val host: String) : Conn
    data object Searching : Conn
    data class Online(val host: String, val laptop: String) : Conn
    data class Offline(val reason: String) : Conn
    data object Unpaired : Conn
}

data class Upload(val id: String, val name: String, val progress: Float, val bps: Double = 0.0, val label: String = "Sending")

/** Result of a speed test, bytes per second each way. */
data class SpeedResult(val down: Double, val up: Double, val at: Long = System.currentTimeMillis())

/**
 * Something to send: a name, a type and a way to read the bytes as a stream (so size doesn't matter).
 * [size] is -1 when unknown. [path] is where it sits inside a zip, e.g. "Holiday/day 2/IMG_1.jpg".
 */
class Outgoing(val name: String, val mime: String, val size: Long = -1, val path: String? = null, val open: () -> InputStream)

/**
 * Talks to the laptop. One instance per process (see [com.pankqx.tonefer.ToneferApp]).
 * All payloads are sealed with the pairing key before they leave the phone.
 */
class ToneferClient(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)   // generous: a busy upload can delay pongs
        .build()
    private val discovery = Discovery(http)

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _uploads = MutableStateFlow<List<Upload>>(emptyList())
    val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

    private val _queued = MutableStateFlow(0)
    /** Items waiting for the laptop (shared while it was unreachable). */
    val queued: StateFlow<Int> = _queued.asStateFlow()

    private val _speed = MutableStateFlow<SpeedResult?>(null)
    val speed: StateFlow<SpeedResult?> = _speed.asStateFlow()

    /** Called for every new item that came from the laptop (notifications, auto-save…). */
    var onIncoming: (suspend (Item) -> Unit)? = null

    /** Called after queued items were delivered. */
    var onQueueDelivered: ((Int) -> Unit)? = null

    /**
     * Whether to keep looking for the laptop for as long as it takes (true while the app is
     * on screen). When false, the search stops on its own: [IDLE_MS] after the laptop left,
     * or [QUEUE_WINDOW_MS] if something is still waiting to be sent.
     */
    var keepSearching: () -> Boolean = { true }

    /** Called when the client stopped looking by itself. Receives the number of items still queued. */
    var onIdle: ((Int) -> Unit)? = null

    /** When the laptop was last lost (0 while connected or not yet looked for). */
    @Volatile private var awaySince = 0L

    private val queueDir get() = File(context.filesDir, "queue").apply { mkdirs() }
    private val flushLock = kotlinx.coroutines.sync.Mutex()

    init {
        _queued.value = queueCount()
    }

    private var loop: Job? = null
    @Volatile private var socket: WebSocket? = null

    val isRunning get() = loop?.isActive == true

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (isRunning) return
        loop = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, null)
        socket = null
        if (_conn.value !is Conn.Unpaired) _conn.value = Conn.Idle
    }

    /** Drop the current connection and search again (e.g. after switching networks). */
    fun reconnect() {
        stop()
        start()
    }

    /** True when it's time to stop searching in the background. */
    private fun shouldRest(): Boolean {
        // Never wind down in the middle of a transfer.
        if (keepSearching() || _uploads.value.isNotEmpty()) { awaySince = 0L; return false }
        val now = System.currentTimeMillis()
        if (awaySince == 0L) { awaySince = now; return false }
        val window = if (_queued.value > 0) QUEUE_WINDOW_MS else IDLE_MS
        return now - awaySince > window
    }

    /** Stops the loop quietly; [start] (opening the app or sharing) resumes it. */
    private fun rest(state: Conn = Conn.Idle) {
        _conn.value = state
        awaySince = 0L
        onIdle?.invoke(_queued.value)
    }

    private suspend fun runLoop() {
        var backoff = 1500L
        awaySince = 0L
        while (currentCoroutineContext().isActive) {
            val pairing = settings.current.pairing ?: run { _conn.value = Conn.Idle; return }
            if (shouldRest()) { rest(); return }
            var host = pairing.host
            _conn.value = Conn.Connecting(host)

            when (discovery.ping(host, pairing)) {
                200 -> Unit
                401 -> { rest(Conn.Unpaired); return }
                else -> {
                    _conn.value = Conn.Searching
                    when (val r = discovery.find(pairing)) {
                        is Discovery.Result.Found -> {
                            host = r.host
                            settings.update { s -> s.copy(pairing = s.pairing?.copy(host = host)) }
                        }
                        Discovery.Result.Unauthorized -> { rest(Conn.Unpaired); return }
                        Discovery.Result.NotFound -> {
                            _conn.value = Conn.Offline("Laptop not found")
                            delay(backoff)
                            // Search harder while something is waiting to be sent.
                            backoff = (backoff * 2).coerceAtMost(if (_queued.value > 0) 4_000 else 15_000)
                            continue
                        }
                    }
                }
            }

            val code = runSocket(pairing.copy(host = host))
            if (code == 4001 || code == 1008) { rest(Conn.Unpaired); return }
            backoff = 1500L
            _conn.value = Conn.Offline("Connection lost")
            delay(1200)
        }
    }

    /** Opens the WebSocket and suspends until it closes. Returns the close code. */
    private suspend fun runSocket(p: Pairing): Int = suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url("ws://${p.host}:${p.port}/ws?t=${p.token}").build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { Box.openJson(p.key, text) } catch (_: Exception) { return }
                handle(p, msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (cont.isActive) cont.resume(code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) cont.resume(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) cont.resume(response?.code ?: -1)
            }
        })
        socket = ws
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun handle(p: Pairing, msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> {
                val arr = msg.optJSONArray("items")
                val list = buildList { if (arr != null) for (i in 0 until arr.length()) add(Item.from(arr.getJSONObject(i))) }
                _items.value = list
                val laptop = msg.optString("host", "Laptop")
                awaySince = 0L
                _conn.value = Conn.Online(p.host, laptop)
                if (_queued.value > 0) scope.launch(Dispatchers.IO) { flushQueue() }
                if (laptop != p.laptop) scope.launch {
                    settings.update { s -> s.copy(pairing = s.pairing?.copy(laptop = laptop)) }
                }
            }
            "item" -> {
                val item = Item.from(msg.getJSONObject("item"))
                _items.update { it + item }
                if (!item.fromPhone) scope.launch { onIncoming?.invoke(item) }
            }
            "deleted" -> {
                val id = msg.optString("id")
                _items.update { list -> list.filterNot { it.id == id } }
            }
            "cleared" -> _items.value = emptyList()
            "speedtest" -> scope.launch(Dispatchers.IO) { runSpeedTest(p, msg.optLong("bytes", 16L * 1024 * 1024)) }
        }
    }

    val isOnline get() = _conn.value is Conn.Online

    suspend fun awaitOnline(timeoutMs: Long = 12_000): Boolean {
        start()
        return withTimeoutOrNull(timeoutMs) { conn.first { it is Conn.Online } } != null
    }

    /** Shows fixed data without a laptop. Used by screenshot tests and previews only. */
    @androidx.annotation.VisibleForTesting
    fun preview(items: List<Item>, conn: Conn) {
        _items.value = items
        _conn.value = conn
    }

    // ------------------------------------------------------------------ sending

    /** Sends a note. If the laptop can't be reached it is queued and sent automatically later. */
    suspend fun sendText(text: String, queueIfOffline: Boolean = true): Boolean {
        if (text.isBlank()) return false
        if (!awaitOnline()) {
            if (queueIfOffline) enqueue(listOf(QueuedText(text)))
            return false
        }
        val p = settings.current.pairing ?: return false
        val payload = JSONObject().put("type", "text").put("text", text)
        return socket?.send(Box.sealJson(p.key, payload)) == true
    }

    /** Sends each file; returns how many arrived. */
    suspend fun sendFiles(files: List<Outgoing>, queueIfOffline: Boolean = true): Int = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext 0
        if (!awaitOnline()) {
            if (queueIfOffline) enqueue(files)
            return@withContext 0
        }
        var ok = 0
        for (f in files) if (upload(f)) ok++
        ok
    }

    /** Sends several files (or a whole folder) as one zip, built on the laptop as they arrive. */
    suspend fun sendZip(name: String, files: List<Outgoing>, queueIfOffline: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext false
        if (!awaitOnline()) {
            if (queueIfOffline) enqueueZip(name, files)
            return@withContext false
        }
        uploadZip(name, files)
    }

    private val cancelled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Stops a transfer shown in [uploads]. It ends at the next chunk and nothing is published. */
    fun cancel(id: String) { cancelled.add(id) }

    private fun setUpload(id: String, progress: Float, bps: Double, name: String? = null) {
        if (cancelled.remove(id)) throw Fatal("Cancelled")
        _uploads.update { l -> l.map { if (it.id == id) it.copy(progress = progress, bps = bps, name = name ?: it.name) else it } }
    }

    private class Fatal(message: String) : Exception(message)

    /** Why the last transfer failed, in plain words, for the screen to show. */
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private fun report(name: String, t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        val why = when {
            t is Fatal && t.message == "Cancelled" -> return
            t is Fatal -> t.message ?: "the laptop refused it"
            t is OutOfMemoryError -> "the phone ran out of memory"
            t is IOException -> "the connection to the laptop was lost (${t.message ?: t.javaClass.simpleName})"
            t is SecurityException -> "Tonefer no longer has permission to read it. Pick it again."
            else -> t.message ?: t.javaClass.simpleName
        }
        errors.tryEmit("Couldn't send $name: $why")
    }

    /** POST with the token. Returns the HTTP code and the JSON body (empty object if none). */
    private fun post(p: Pairing, path: String, body: RequestBody): Pair<Int, JSONObject> {
        val req = Request.Builder().url("${p.baseUrl}$path").header("X-Tonefer-Token", p.token).post(body).build()
        return http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("_text", raw.take(200)) }
            r.code to json
        }
    }

    private fun delete(p: Pairing, path: String) {
        try {
            http.newCall(Request.Builder().url("${p.baseUrl}$path").header("X-Tonefer-Token", p.token).delete().build()).execute().close()
        } catch (_: Exception) { }
    }

    private fun sealedBody(p: Pairing, json: JSONObject) = Box.sealJson(p.key, json).toRequestBody(TEXT)

    private fun readFull(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /**
     * Streams one file to the laptop in sealed chunks. Memory use stays flat whatever the size,
     * and a chunk lost to a network hiccup is simply sent again.
     * Returns false if the laptop is too old for chunked transfers (caller falls back).
     */
    private suspend fun streamUp(
        p: Pairing, f: Outgoing, name: String, mime: String, input: InputStream, size: Long,
        zip: String?, onBytes: (Long) -> Unit,
    ): Boolean {
        val meta = JSONObject().put("name", name).put("mime", mime).put("size", size)
        if (zip != null) meta.put("zip", zip).put("path", f.path ?: name)
        val (code, start) = post(p, "/api/upload/start", sealedBody(p, meta))
        if (code == 404 || code == 405) { if (zip == null) return false else throw Fatal("Update Tonefer on your laptop to send zips") }
        if (code !in 200..299) throw Fatal(start.optString("_text").ifBlank { "The laptop refused the transfer ($code)" })
        val tid = start.getString("id")
        val chunk = start.optInt("chunk", 4 * 1024 * 1024)
        var cur = ByteArray(chunk)
        var next = ByteArray(chunk)
        var curN = readFull(input, cur)
        var index = 0
        try {
            while (true) {
                // Read one chunk ahead, so we know whether this one is the last.
                val nextN = if (curN == chunk) readFull(input, next) else 0
                val last = nextN == 0
                val body = Chunks.seal(p.key, tid, index, last, cur, curN).toRequestBody(BINARY)
                var tries = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val (c, r) = post(p, "/api/upload/$tid/$index", body)
                        if (c in 200..299) break
                        if (c == 409 && r.optInt("next", -1) == index + 1) break   // it did arrive
                        if (c == 401 || c == 403) throw Fatal("Pairing changed. Scan the code on your laptop again.")
                        if (c == 404 || c == 409 || c == 400) throw Fatal("Transfer was interrupted")
                        throw IOException("HTTP $c")
                    } catch (e: IOException) {
                        // Hotspot hiccup or the laptop briefly asleep: keep trying for about five minutes.
                        if (++tries > 34) throw e
                        delay(minOf(1000L shl minOf(tries - 1, 4), 10_000L))
                    }
                }
                onBytes(curN.toLong())
                if (last) break
                val t = cur; cur = next; next = t
                curN = nextN
                index++
            }
        } catch (e: Exception) {
            if (zip == null) delete(p, "/api/upload/$tid")
            throw e
        }
        return true
    }

    private suspend fun upload(f: Outgoing): Boolean {
        val p = settings.current.pairing ?: return false
        val id = UUID.randomUUID().toString()
        _uploads.update { it + Upload(id, f.name, 0f) }
        return try {
            var name = f.name
            var mime = f.mime
            var size = f.size
            var small: ByteArray? = null
            val quality = settings.current.imageQuality
            // Photos are resized in memory (they're small); everything else is streamed.
            if (quality.maxSide != null && mime.startsWith("image/") && mime != "image/gif" && f.size in 0..(64L shl 20)) {
                val bytes = f.open().use { it.readBytes() }
                small = shrink(bytes, quality)?.also {
                    mime = "image/jpeg"
                    name = name.substringBeforeLast('.', name) + ".jpg"
                } ?: bytes
                size = small.size.toLong()
            }
            val start = System.nanoTime()
            var sent = 0L
            val input = small?.inputStream() ?: f.open()
            val ok = input.use {
                streamUp(p, f, name, mime, it, size, null) { n ->
                    sent += n
                    val secs = (System.nanoTime() - start) / 1e9
                    setUpload(id, if (size > 0) sent.toFloat() / size else 0f, if (secs > 0.2) sent / secs else 0.0)
                }
            }
            ok || legacyUpload(p, name, mime, small ?: run {
                if (f.size < 0 || f.size > 200L * 1024 * 1024) throw Fatal("Update Tonefer on your laptop to send files this big")
                f.open().use { it.readBytes() }
            })
        } catch (t: Throwable) {
            report(f.name, t)
            false
        } finally {
            _uploads.update { l -> l.filterNot { it.id == id } }
        }
    }

    /** Single-request upload for laptops running a Tonefer older than 1.5. */
    private fun legacyUpload(p: Pairing, name: String, mime: String, bytes: ByteArray): Boolean {
        val meta = Box.sealJson(p.key, JSONObject().put("name", name).put("mime", mime))
        val req = Request.Builder().url("${p.baseUrl}/api/upload")
            .header("X-Tonefer-Token", p.token).header("X-Tonefer-Meta", meta)
            .post(Box.seal(p.key, bytes).toRequestBody(BINARY)).build()
        return http.newCall(req).execute().use { it.isSuccessful }
    }

    private suspend fun uploadZip(zipName: String, files: List<Outgoing>): Boolean {
        val p = settings.current.pairing ?: return false
        val id = UUID.randomUUID().toString()
        val shown = if (zipName.endsWith(".zip", true)) zipName else "$zipName.zip"
        _uploads.update { it + Upload(id, shown, 0f) }
        var zip: String? = null
        return try {
            val total = files.sumOf { it.size.coerceAtLeast(0) }
            val (code, z) = post(p, "/api/zip/start", sealedBody(p, JSONObject().put("name", zipName).put("files", files.size).put("size", total)))
            if (code == 404 || code == 405) throw Fatal("Update Tonefer on your laptop to send zips")
            if (code !in 200..299) throw Fatal("Laptop refused the transfer ($code)")
            zip = z.getString("zip")
            val start = System.nanoTime()
            var sent = 0L
            for ((n, f) in files.withIndex()) {
                f.open().use { input ->
                    streamUp(p, f, f.name, f.mime, input, f.size, zip) { b ->
                        sent += b
                        val secs = (System.nanoTime() - start) / 1e9
                        setUpload(id, if (total > 0) (sent.toFloat() / total).coerceAtMost(1f) else 0f,
                            if (secs > 0.2) sent / secs else 0.0, "$shown · ${n + 1} of ${files.size}")
                    }
                }
            }
            val (fc, _) = post(p, "/api/zip/$zip/finish", sealedBody(p, JSONObject().put("zip", zip)))
            fc in 200..299
        } catch (t: Throwable) {
            zip?.let { delete(p, "/api/zip/$it") }
            report(shown, t)
            false
        } finally {
            _uploads.update { l -> l.filterNot { it.id == id } }
        }
    }

    /** Re-encodes a photo so its longest side is at most [ImageQuality.maxSide]. Keeps orientation. */
    private fun shrink(bytes: ByteArray, quality: ImageQuality): ByteArray? = try {
        val max = quality.maxSide ?: return null
        val src = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        var bigger = false
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > max) {
                bigger = true
                val scale = max.toFloat() / longest
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (!bigger) null else ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    fun outgoingFrom(uri: Uri): Outgoing {
        val cr = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (_: Exception) { }
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        } catch (_: Exception) { }
        val mime = cr.getType(uri) ?: guessMime(name)
        return Outgoing(name, mime, size) {
            cr.openInputStream(uri) ?: throw IllegalStateException("Can't read $name")
        }
    }

    // ------------------------------------------------------------------ receiving

    /** Downloads (once) and decrypts a file from the laptop into the private cache. */
    suspend fun fileFor(item: Item): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "received/${item.id}").apply { mkdirs() }
        val out = File(dir, safeName(item.name ?: "file"))
        if (out.exists() && out.length() > 0) return@withContext out
        val p = settings.current.pairing ?: throw IllegalStateException("Not paired")
        val req = Request.Builder()
            .url("${p.baseUrl}/api/file/${item.id}?v=2")
            .header("X-Tonefer-Token", p.token)
            .build()
        val tmp = File(dir, out.name + ".part")
        val big = item.size > 4L * 1024 * 1024
        val rowId = UUID.randomUUID().toString()
        if (big) _uploads.update { it + Upload(rowId, item.name ?: "file", 0f, label = "Receiving") }
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException(when (r.code) {
                    410 -> "File was removed on the laptop"
                    413 -> "Update Tonefer on your laptop to receive files this big"
                    else -> "Download failed (${r.code})"
                })
                val src = r.body!!.source()
                if (r.header("X-Tonefer-Format") != "2") {
                    tmp.writeBytes(Box.open(p.key, src.readByteArray()))   // laptop older than 1.5
                } else {
                    val total = r.header("X-Tonefer-Size")?.toLongOrNull() ?: item.size
                    val start = System.nanoTime()
                    var got = 0L
                    var index = 0
                    var last = false
                    tmp.outputStream().buffered().use { o ->
                        while (!src.exhausted()) {
                            val n = src.readInt()
                            if (n <= 0 || n > 16 * 1024 * 1024) throw IOException("Damaged transfer")
                            val c = Chunks.open(p.key, src.readByteArray(n.toLong()))
                            if (c.tid != item.id || c.index != index) throw IOException("Damaged transfer")
                            o.write(c.plain, c.offset, c.length)
                            got += c.length; index++; last = c.last
                            if (big) {
                                val secs = (System.nanoTime() - start) / 1e9
                                setUpload(rowId, if (total > 0) got.toFloat() / total else 0f, if (secs > 0.2) got / secs else 0.0)
                            }
                        }
                    }
                    if (!last) throw IOException("Connection dropped")
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            if (big) _uploads.update { l -> l.filterNot { it.id == rowId } }
        }
        tmp.renameTo(out)
        out
    }

    // ------------------------------------------------------------------ offline queue

    private class QueuedText(val text: String)

    /** Stores items on disk until the laptop is reachable again. */
    private suspend fun enqueue(entries: List<Any>) = withContext(Dispatchers.IO) {
        for (e in entries) {
            val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val meta = JSONObject()
            when (e) {
                is QueuedText -> meta.put("type", "text").put("text", e.text)
                is Outgoing -> {
                    val bin = File(queueDir, "$id.bin")
                    try { e.open().use { i -> bin.outputStream().use { o -> i.copyTo(o, COPY) } } } catch (_: Exception) { bin.delete(); continue }
                    meta.put("type", "file").put("name", e.name).put("mime", e.mime)
                }
            }
            File(queueDir, "$id.json").writeText(meta.toString())
        }
        _queued.value = queueCount()
        start()
    }

    /** A folder or group of files waiting to go as one zip: its own directory with a manifest. */
    private suspend fun enqueueZip(name: String, files: List<Outgoing>) = withContext(Dispatchers.IO) {
        val dir = File(queueDir, "zip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}").apply { mkdirs() }
        val entries = org.json.JSONArray()
        for ((n, f) in files.withIndex()) {
            val bin = File(dir, "$n.bin")
            try { f.open().use { i -> bin.outputStream().use { o -> i.copyTo(o, COPY) } } } catch (_: Exception) { bin.delete(); continue }
            entries.put(JSONObject().put("file", bin.name).put("name", f.name).put("mime", f.mime).put("path", f.path ?: f.name))
        }
        if (entries.length() == 0) { dir.deleteRecursively(); return@withContext }
        File(dir, "manifest").writeText(JSONObject().put("name", name).put("entries", entries).toString())
        _queued.value = queueCount()
        start()
    }

    private fun queueCount(): Int =
        queueDir.listFiles { f -> f.name.endsWith(".json") || (f.isDirectory && File(f, "manifest").exists()) }?.size ?: 0

    /** Public entry point for the share sheet: queue now, deliver as soon as the laptop appears. */
    suspend fun queue(text: String?, files: List<Outgoing>, zipName: String? = null) {
        if (zipName != null && files.isNotEmpty()) enqueueZip(zipName, files)
        else enqueue(buildList { if (text != null) add(QueuedText(text)); addAll(files) })
    }

    private suspend fun flushQueue() = flushLock.withLock {
        val entries = queueDir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return@withLock
        var delivered = 0
        for (metaFile in entries) {
            if (!isOnline) break
            val meta = try { JSONObject(metaFile.readText()) } catch (_: Exception) { metaFile.delete(); continue }
            val data = File(queueDir, metaFile.name.removeSuffix(".json") + ".bin")
            val ok = when (meta.optString("type")) {
                "text" -> {
                    val p = settings.current.pairing
                    p != null && socket?.send(Box.sealJson(p.key, JSONObject().put("type", "text").put("text", meta.optString("text")))) == true
                }
                else -> data.exists() && upload(Outgoing(meta.optString("name", "file"), meta.optString("mime", "application/octet-stream"), data.length()) { data.inputStream() })
            }
            if (ok) {
                metaFile.delete(); data.delete(); delivered++
                _queued.value = (_queued.value - 1).coerceAtLeast(0)
            } else break
        }
        // Folders and groups queued as zips.
        val zips = queueDir.listFiles { f -> f.isDirectory && File(f, "manifest").exists() }?.sortedBy { it.name }.orEmpty()
        for (dir in zips) {
            if (!isOnline) break
            val m = try { JSONObject(File(dir, "manifest").readText()) } catch (_: Exception) { dir.deleteRecursively(); continue }
            val arr = m.optJSONArray("entries") ?: org.json.JSONArray()
            val files = (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                val bin = File(dir, e.optString("file"))
                if (!bin.exists()) null
                else Outgoing(e.optString("name", "file"), e.optString("mime", "application/octet-stream"), bin.length(), e.optString("path")) { bin.inputStream() }
            }
            if (files.isEmpty() || uploadZip(m.optString("name", "Tonefer"), files)) {
                dir.deleteRecursively(); delivered++
                _queued.value = queueCount()
            } else break
        }
        if (delivered > 0) onQueueDelivered?.invoke(delivered)
    }

    fun clearQueue() {
        queueDir.deleteRecursively()
        _queued.value = 0
    }

    // ------------------------------------------------------------------ speed test

    /** Answers a speed test started from the laptop: download then upload [bytes] of filler. */
    private suspend fun runSpeedTest(p: Pairing, bytes: Long) = withContext(Dispatchers.IO) {
        try {
            val t0 = System.nanoTime()
            http.newCall(Request.Builder().url("${p.baseUrl}/api/speedtest?n=$bytes").header("X-Tonefer-Token", p.token).build())
                .execute().use { r ->
                    val src = r.body!!.source()
                    val buf = okio.Buffer()
                    while (src.read(buf, 256L * 1024) != -1L) buf.clear()
                }
            val down = bytes / ((System.nanoTime() - t0) / 1e9)
            val filler = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = bytes
                override fun writeTo(sink: BufferedSink) {
                    val chunk = ByteArray(CHUNK)
                    var left = bytes
                    while (left > 0) { val n = minOf(CHUNK.toLong(), left).toInt(); sink.write(chunk, 0, n); left -= n }
                }
            }
            val t1 = System.nanoTime()
            http.newCall(Request.Builder().url("${p.baseUrl}/api/speedtest").header("X-Tonefer-Token", p.token).post(filler).build())
                .execute().close()
            val up = bytes / ((System.nanoTime() - t1) / 1e9)
            _speed.value = SpeedResult(down, up)
        } catch (_: Exception) { }
    }

    fun clearCache() {
        File(context.cacheDir, "received").deleteRecursively()
        File(context.cacheDir, "outgoing").deleteRecursively()
    }

    companion object {
        private const val CHUNK = 64 * 1024
        private const val COPY = 256 * 1024
        private val BINARY = "application/octet-stream".toMediaType()
        private val TEXT = "text/plain".toMediaType()

        /** Background search time after the laptop leaves, with nothing to send. */
        const val IDLE_MS = 60_000L

        /** Background search time while shared items are waiting for the laptop. */
        const val QUEUE_WINDOW_MS = 10 * 60_000L

        fun safeName(name: String): String =
            name.substringAfterLast('/').replace(Regex("""[\\:*?"<>|\u0000-\u001f]"""), "_").take(150).ifBlank { "file" }

        fun guessMime(name: String): String =
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
