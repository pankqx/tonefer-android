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
import com.pankqx.tonefer.ui.MainActivity
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
        Toast.makeText(this, "Sending to $laptop…", Toast.LENGTH_SHORT).show()
        app.client.start()

        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { stage(app.client, uris) }
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
                        ok == 0 -> "Couldn't reach your laptop. Is Tonefer running there?"
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
