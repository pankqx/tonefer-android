package com.pankqx.tonefer

import com.pankqx.tonefer.crypto.SecretBox
import com.pankqx.tonefer.p2p.HostCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64

/** Phone to phone: the host speaks the same protocol as the laptop (upload, download, auth). */
class HostCoreTest {
    private lateinit var dir: File
    private lateinit var host: HostCore
    private val got = mutableListOf<HostCore.Item>()
    private var port = 0
    private lateinit var key: ByteArray
    private lateinit var token: String

    @Before fun setUp() {
        dir = Files.createTempDirectory("host").toFile()
        val page = "<html>tonefer</html>".toByteArray()
        host = HostCore(dir, { path -> if (path == "phone/index.html") page else throw java.io.IOException("missing") }, object : HostCore.Listener {
            override fun onItem(item: HostCore.Item) { synchronized(got) { got += item } }
            override fun onDeleted(id: String) {}
            override fun onGuests(connected: Int) {}
            override fun onProgress(id: String, label: String, name: String, done: Long, total: Long, bytesPerSecond: Double) {}
            override fun onProgressDone(id: String) {}
        }, "Test phone")
        port = host.start(28765)
        key = Base64.getUrlDecoder().decode(host.keyB64Url())
        token = HostCore.tokenFor(key)
    }

    @After fun tearDown() { host.stop(); dir.deleteRecursively() }

    private fun call(method: String, path: String, body: ByteArray? = null, auth: Boolean = true): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (auth) c.setRequestProperty("X-Tonefer-Token", token)
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    private fun sealJson(json: String) = Base64.getEncoder().encodeToString(SecretBox.seal(key, json.toByteArray())).toByteArray()
    private fun field(json: String, name: String) = Regex("\"$name\":\"?([^\",}]+)").find(json)!!.groupValues[1]

    @Test fun servesPageAndChecksToken() {
        assertEquals(200, call("GET", "/").first)
        assertEquals(200, call("GET", "/api/ping").first)
        assertEquals(401, call("GET", "/api/ping", auth = false).first)
        assertEquals(404, call("GET", "/static/../secret").first)
    }

    @Test fun chunkedUploadArrivesIntact() {
        val data = ByteArray(9 * 1024 * 1024 + 7) { (it * 31).toByte() }
        val (_, start) = call("POST", "/api/upload/start", sealJson("""{"name":"clip.mp4","mime":"video/mp4","size":${data.size}}"""))
        val tid = field(String(start), "id")
        val chunk = 4 * 1024 * 1024
        val count = (data.size + chunk - 1) / chunk
        for (i in 0 until count) {
            val part = data.copyOfRange(i * chunk, minOf(data.size, (i + 1) * chunk))
            val header = ByteBuffer.allocate(21).put(tid.toByteArray()).putInt(i).put(if (i == count - 1) 1 else 0).array()
            val (code, _) = call("POST", "/api/upload/$tid/$i", SecretBox.seal(key, header + part))
            assertEquals(200, code)
        }
        val item = synchronized(got) { got.single() }
        assertEquals("clip.mp4", item.name)
        assertTrue(item.file.readBytes().contentEquals(data))
    }

    @Test fun framedDownloadMatches() {
        val f = File(dir, "out.bin").apply { writeBytes(ByteArray(2_500_000) { (it % 251).toByte() }) }
        val item = host.sendFile(f, "out.bin", "application/octet-stream")
        val (code, body) = call("GET", "/api/file/${item.id}?v=2")
        assertEquals(200, code)
        val buf = ByteBuffer.wrap(body)
        val out = java.io.ByteArrayOutputStream()
        while (buf.hasRemaining()) {
            val n = buf.int
            val sealed = ByteArray(n).also { buf.get(it) }
            val plain = SecretBox.open(key, sealed)
            out.write(plain, 21, plain.size - 21)
        }
        assertTrue(out.toByteArray().contentEquals(f.readBytes()))
    }
}
