package com.pankqx.tonefer.p2p

import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.service.Notifications
import com.pankqx.tonefer.util.Files
import com.pankqx.tonefer.util.Folders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phone to phone. This phone becomes the "laptop": it shows a QR code, and any other phone
 * (iPhone or Android, no app needed) scans it with its camera, opens the Tonefer page served from
 * here and swaps notes, photos, files and folders over this phone's hotspot. Same encryption as
 * with the laptop: the key travels only inside the QR code.
 */
class PhoneHost(private val app: ToneferApp) {

    data class State(
        val running: Boolean = false,
        val port: Int = 0,
        /** IPv4 addresses the other phone can reach, best first (hotspot before Wi‑Fi). */
        val addresses: List<String> = emptyList(),
        val chosen: String? = null,
        val key: String = "",
        val guests: Int = 0,
        val error: String? = null,
    ) {
        val address get() = chosen?.takeIf { it in addresses } ?: addresses.firstOrNull()
        /** What the QR code holds. p=phone tells the page the other side is a phone. */
        val url get() = address?.let { "http://$it:$port/#k=$key&p=phone" }
    }

    data class Transfer(val id: String, val label: String, val name: String, val progress: Float, val bps: Double)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    /** One-off messages for a toast (cleared by the screen after showing). */
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private var core: HostCore? = null
    private val received = HashMap<String, File>()
    private var idleJob: Job? = null
    private val wake by lazy {
        (app.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tonefer:host").apply { setReferenceCounted(false) }
    }

    val dir: File get() = File(app.cacheDir, "p2p")

    fun consumeMessage() { _messages.value = null }

    /** For screenshots and tests: show a state without opening a socket. */
    fun preview(state: State, items: List<Item> = emptyList(), transfers: List<Transfer> = emptyList()) {
        _state.value = state
        _items.value = items
        _transfers.value = transfers
    }

    @Synchronized
    fun start() {
        if (core?.isRunning == true) return
        dir.deleteRecursively()   // a new session starts clean
        dir.mkdirs()
        val c = HostCore(dir, { path -> app.assets.open("web/$path").use { it.readBytes() } }, Listener(), deviceName())
        try {
            val port = c.start(PORT)
            core = c
            _items.value = emptyList()
            received.clear()
            _state.value = State(running = true, port = port, addresses = addresses(), key = c.keyB64Url())
            wake.acquire(4 * 60 * 60 * 1000L)
            HostService.start(app)
            watchIdle()
        } catch (e: Exception) {
            c.stop()
            _state.value = State(error = "Couldn't start: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        core?.stop()
        core = null
        idleJob?.cancel()
        if (wake.isHeld) wake.release()
        _state.value = State()
        _transfers.value = emptyList()
        HostService.stop(app)
    }

    /** A fresh key: the other phone is disconnected and must scan again. */
    fun newCode() {
        val c = core ?: return
        c.newKey()
        _state.update { it.copy(key = c.keyB64Url(), guests = 0) }
    }

    /** The hotspot may have been turned on after starting: look at the network again. */
    fun refreshAddresses() {
        if (core == null) return
        val now = addresses()
        if (now != _state.value.addresses) _state.update { it.copy(addresses = now) }
    }

    fun choose(address: String) = _state.update { it.copy(chosen = address) }

    fun sendText(text: String) {
        val c = core ?: return
        if (text.isNotBlank()) app.scope.launch(Dispatchers.IO) { c.sendText(text) }
    }

    /** Offers picked photos and files. They are read in place, not copied. */
    fun sendUris(uris: List<Uri>) {
        val c = core ?: return
        app.scope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val o = app.client.outgoingFrom(uri)
                try {
                    if (o.size >= 0) {
                        c.sendSource(o.name, o.mime, o.size, 0, object : HostCore.Source {
                            override fun open() = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Can't read ${o.name}")
                        })
                    } else {
                        val f = unique(c.outbox(), o.name)
                        o.open().use { input -> f.outputStream().use { input.copyTo(it) } }
                        c.sendFile(f, f.name, o.mime)
                    }
                } catch (e: Exception) {
                    _messages.value = "Couldn't add ${o.name}: ${e.message}"
                }
            }
        }
    }

    /** Packs a folder into one zip (photos and videos stored as they are) and offers it. */
    fun sendFolder(tree: Uri) {
        val c = core ?: return
        app.scope.launch(Dispatchers.IO) {
            val picked = try { Folders.walk(app, tree) } catch (e: Exception) { _messages.value = "Couldn't read that folder"; return@launch }
            if (picked.files.isEmpty()) { _messages.value = "That folder is empty"; return@launch }
            val id = "zip-" + System.nanoTime()
            val zip = unique(c.outbox(), picked.name + ".zip")
            try {
                var done = 0L
                val t0 = System.nanoTime()
                var shown = 0L
                ZipOutputStream(zip.outputStream().buffered(256 * 1024)).use { zos ->
                    val used = HashSet<String>()
                    for (f in picked.files) {
                        var path = HostCore.cleanPath(f.path ?: (picked.name + "/" + f.name))
                        var n = 1
                        while (!used.add(path)) path = path.substringBeforeLast('.') + " (${n++})" + (path.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
                        val ext = path.substringAfterLast('.', "").lowercase()
                        zos.setLevel(if (ext in STORED) 0 else 1)
                        zos.putNextEntry(ZipEntry(path))
                        val buf = ByteArray(256 * 1024)
                        f.open().use { input ->
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                zos.write(buf, 0, r)
                                done += r
                                if (System.nanoTime() - shown < 200_000_000L) continue
                                shown = System.nanoTime()
                                setTransfer(Transfer(id, "Packing", zip.name, if (picked.bytes > 0) done.toFloat() / picked.bytes else 0f,
                                    done / ((System.nanoTime() - t0) / 1e9).coerceAtLeast(.2)))
                            }
                        }
                        zos.closeEntry()
                    }
                }
                c.sendSource(zip.name, "application/zip", zip.length(), picked.files.size, object : HostCore.Source {
                    override fun open() = zip.inputStream()
                    override fun available() = zip.exists()
                })
            } catch (e: Exception) {
                zip.delete()
                _messages.value = "Couldn't pack ${picked.name}: ${e.message}"
            } finally {
                clearTransfer(id)
            }
        }
    }

    fun delete(id: String) {
        core?.delete(id)
    }

    /** The saved copy of something the other phone sent. */
    fun fileFor(item: Item): File? = received[item.id]

    // ------------------------------------------------------------------ internals

    private inner class Listener : HostCore.Listener {
        override fun onItem(it: HostCore.Item) {
            val item = Item(it.id, it.kind, it.origin, it.ts, it.text, it.name, it.size, it.mime, it.files)
            it.file?.let { f -> synchronized(received) { received[it.id] = f } }
            _items.update { list -> list + item }
            if (it.fromGuest()) app.scope.launch { onIncoming(item, it.file) }
        }

        override fun onDeleted(id: String) = _items.update { list -> list.filterNot { it.id == id } }

        override fun onGuests(connected: Int) {
            _state.update { it.copy(guests = connected) }
        }

        override fun onProgress(id: String, label: String, name: String, done: Long, total: Long, bytesPerSecond: Double) =
            setTransfer(Transfer(id, label, name, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f, bytesPerSecond))

        override fun onProgressDone(id: String) = clearTransfer(id)
    }

    private fun setTransfer(t: Transfer) = _transfers.update { list ->
        if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t
    }

    private fun clearTransfer(id: String) = _transfers.update { list -> list.filterNot { it.id == id } }

    private suspend fun onIncoming(item: Item, file: File?) {
        val s = app.settings.current
        var savedTo: String? = null
        if (item.isText && s.autoCopyText) withContext(Dispatchers.Main) { Files.copyText(app, item.text.orEmpty()) }
        val wantSave = (item.isImage || item.isVideo) && s.autoSavePhotos || (!item.isText && !item.isImage && !item.isVideo && s.autoSaveFiles)
        if (wantSave && file != null) {
            try {
                savedTo = withContext(Dispatchers.IO) { Files.saveToDevice(app, file, item.name ?: file.name, item.mime ?: "application/octet-stream") }
            } catch (_: Exception) { }
        }
        if (!app.inForeground && s.notify) Notifications.incoming(app, item, "the other phone", s.vibrate, savedTo)
    }

    /** Stops by itself after a while with nobody connected and nothing moving, to spare the battery. */
    private fun watchIdle() {
        idleJob?.cancel()
        idleJob = app.scope.launch {
            var quiet = 0
            while (true) {
                delay(60_000)
                val busy = _state.value.guests > 0 || _transfers.value.isNotEmpty() || app.inForeground
                quiet = if (busy) 0 else quiet + 1
                if (quiet >= IDLE_MINUTES) { stop(); return@launch }
            }
        }
    }

    private fun deviceName(): String {
        val name = try { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Phone"
    }

    companion object {
        const val PORT = 8765
        private const val IDLE_MINUTES = 15
        private val STORED = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "mov", "m4v", "mkv",
            "webm", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "7z", "rar", "gz", "apk", "pdf")
        private val SKIP = Regex("^(lo|rmnet|ccmni|tun|ppp|dummy|ipsec|v4-|clat|ifb|sit|ip6).*")
        private val HOTSPOT = Regex("^(ap|swlan|softap|wlan[1-9]|uap|wigig|rndis|ncm|usb).*")

        /** Local IPv4 addresses, hotspot / USB tethering first. */
        fun addresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !SKIP.matches(it.name) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif.name to it.hostAddress!! } }
                .sortedBy { (name, _) -> if (HOTSPOT.matches(name)) 0 else 1 }
                .map { it.second }
                .distinct()
        } catch (_: Exception) { emptyList() }

        private fun unique(folder: File, name: String): File {
            folder.mkdirs()
            val clean = HostCore.safeName(name)
            var f = File(folder, clean)
            var n = 1
            while (f.exists()) f = File(folder, clean.substringBeforeLast('.') + " (${n++})" + (clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }))
            return f
        }
    }
}
