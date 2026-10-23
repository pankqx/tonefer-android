package com.pankqx.tonefer.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.net.Outgoing
import com.pankqx.tonefer.net.ToneferClient
import com.pankqx.tonefer.service.ConnectionService
import com.pankqx.tonefer.service.Notifications
import com.pankqx.tonefer.ui.MainActivity
import com.pankqx.tonefer.util.Hotspot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Invisible target for "Share → Tonefer". Copies what was shared into private storage
 * (shared-URI permissions end when this activity finishes), hands it to the app-wide client
 * and closes immediately, so sharing is a single tap.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ToneferApp
        if (app.settings.current.pairing == null) {
            Toast.makeText(this, "Pair Tonefer with your laptop first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        val uris = streams(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() }
        if (uris.isEmpty() && text.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val laptop = app.settings.current.pairing?.laptop?.ifBlank { null } ?: "laptop"
        app.client.start()
        if (app.settings.current.background) ConnectionService.start(this)

        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { stage(app.client, uris) }
            // Quick check: is the laptop there? If not, keep the item and open the hotspot switch.
            if (!app.client.awaitOnline(2_500)) {
                app.client.queue(text, staged)
                cleanup()
                val waiting = app.client.queued.value
                Notifications.queued(app, waiting)
                Toast.makeText(app, "Laptop not reachable. Turn on your hotspot and it will be sent automatically.", Toast.LENGTH_LONG).show()
                Hotspot.open(this@ShareActivity)
                finish()
                return@launch
            }
            Toast.makeText(app, "Sending to $laptop…", Toast.LENGTH_SHORT).show()
            app.scope.launch {
                val ok = if (text != null) {
                    if (app.client.sendText(text)) 1 else 0
                } else {
                    app.client.sendFiles(staged).also { cleanup() }
                }
                val total = if (text != null) 1 else staged.size
                withContext(Dispatchers.Main) {
                    val msg = when {
                        ok == total && total == 1 -> "Sent to $laptop"
                        ok == total -> "Sent $total items to $laptop"
                        ok == 0 -> "Laptop dropped out. Queued, will send when it's back."
                        else -> "Sent $ok of $total to $laptop"
                    }
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }

    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private fun outDir() = File(cacheDir, "outgoing").apply { mkdirs() }

    /** Reads every shared item into a private file while we still hold permission to read it. */
    private fun stage(client: ToneferClient, uris: List<Uri>): List<Outgoing> = uris.mapNotNull { uri ->
        try {
            val src = client.outgoingFrom(uri)
            val file = File(outDir(), "${System.nanoTime()}_${ToneferClient.safeName(src.name)}")
            file.writeBytes(src.read())
            Outgoing(src.name, src.mime) { file.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanup() {
        outDir().listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60_000) it.delete() }
    }
}
