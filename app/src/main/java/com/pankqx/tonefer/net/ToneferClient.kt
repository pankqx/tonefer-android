package com.pankqx.tonefer.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.pankqx.tonefer.crypto.Box
import com.pankqx.tonefer.data.ImageQuality
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.data.Pairing
import com.pankqx.tonefer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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

data class Upload(val id: String, val name: String, val progress: Float)

/** Something to send: a name, a type and a way to read the bytes. */
class Outgoing(val name: String, val mime: String, val read: () -> ByteArray)

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
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val discovery = Discovery(http)

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _uploads = MutableStateFlow<List<Upload>>(emptyList())
    val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

    /** Called for every new item that came from the laptop (notifications, auto-save…). */
    var onIncoming: (suspend (Item) -> Unit)? = null

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

    private suspend fun runLoop() {
        var backoff = 1500L
        while (currentCoroutineContext().isActive) {
            val pairing = settings.current.pairing ?: run { _conn.value = Conn.Idle; return }
            var host = pairing.host
            _conn.value = Conn.Connecting(host)

            when (discovery.ping(host, pairing)) {
                200 -> Unit
                401 -> { _conn.value = Conn.Unpaired; return }
                else -> {
                    _conn.value = Conn.Searching
                    when (val r = discovery.find(pairing)) {
                        is Discovery.Result.Found -> {
                            host = r.host
                            settings.update { s -> s.copy(pairing = s.pairing?.copy(host = host)) }
                        }
                        Discovery.Result.Unauthorized -> { _conn.value = Conn.Unpaired; return }
                        Discovery.Result.NotFound -> {
                            _conn.value = Conn.Offline("Laptop not found")
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(15_000)
                            continue
                        }
                    }
                }
            }

            val code = runSocket(pairing.copy(host = host))
            if (code == 4001 || code == 1008) { _conn.value = Conn.Unpaired; return }
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
                _conn.value = Conn.Online(p.host, laptop)
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
        }
    }

    private suspend fun awaitOnline(timeoutMs: Long = 12_000): Boolean {
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

    suspend fun sendText(text: String): Boolean {
        if (text.isBlank()) return false
        if (!awaitOnline()) return false
        val p = settings.current.pairing ?: return false
        val payload = JSONObject().put("type", "text").put("text", text)
        return socket?.send(Box.sealJson(p.key, payload)) == true
    }

    /** Sends each file; returns how many arrived. */
    suspend fun sendFiles(files: List<Outgoing>): Int = withContext(Dispatchers.IO) {
        if (files.isEmpty() || !awaitOnline()) return@withContext 0
        var ok = 0
        for (f in files) if (upload(f)) ok++
        ok
    }

    private suspend fun upload(f: Outgoing): Boolean {
        val p = settings.current.pairing ?: return false
        val id = UUID.randomUUID().toString()
        _uploads.update { it + Upload(id, f.name, 0f) }
        return try {
            var name = f.name
            var mime = f.mime
            var bytes = f.read()
            val quality = settings.current.imageQuality
            if (quality.maxSide != null && mime.startsWith("image/") && mime != "image/gif") {
                shrink(bytes, quality)?.let { small ->
                    bytes = small
                    mime = "image/jpeg"
                    name = name.substringBeforeLast('.', name) + ".jpg"
                }
            }
            if (bytes.size > MAX_BYTES) throw IllegalStateException("File is larger than 400 MB")
            val sealed = Box.seal(p.key, bytes)
            val meta = Box.sealJson(p.key, JSONObject().put("name", name).put("mime", mime))
            val body = ProgressBody(sealed) { prog -> _uploads.update { l -> l.map { if (it.id == id) it.copy(progress = prog) else it } } }
            val req = Request.Builder()
                .url("${p.baseUrl}/api/upload")
                .header("X-Tonefer-Token", p.token)
                .header("X-Tonefer-Meta", meta)
                .post(body)
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
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
        val mime = cr.getType(uri) ?: guessMime(name)
        return Outgoing(name, mime) {
            cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Can't read $name")
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
            .url("${p.baseUrl}/api/file/${item.id}")
            .header("X-Tonefer-Token", p.token)
            .build()
        val sealed = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException(if (r.code == 410) "File was removed on the laptop" else "Download failed (${r.code})")
            r.body!!.bytes()
        }
        val tmp = File(dir, out.name + ".part")
        tmp.writeBytes(Box.open(p.key, sealed))
        tmp.renameTo(out)
        out
    }

    fun clearCache() {
        File(context.cacheDir, "received").deleteRecursively()
        File(context.cacheDir, "outgoing").deleteRecursively()
    }

    private class ProgressBody(private val data: ByteArray, private val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = data.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0
            var lastReport = 0f
            while (sent < data.size) {
                val n = minOf(CHUNK, data.size - sent)
                sink.write(data, sent, n)
                sent += n
                val p = sent.toFloat() / data.size
                if (p - lastReport > 0.02f || sent == data.size) { onProgress(p); lastReport = p }
            }
        }
    }

    companion object {
        private const val CHUNK = 64 * 1024
        private const val MAX_BYTES = 400 * 1024 * 1024

        fun safeName(name: String): String =
            name.substringAfterLast('/').replace(Regex("""[\\:*?"<>|\u0000-\u001f]"""), "_").take(150).ifBlank { "file" }

        fun guessMime(name: String): String =
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
