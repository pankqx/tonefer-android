package com.pankqx.tonefer

import android.app.Application
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.data.SettingsStore
import com.pankqx.tonefer.net.Conn
import com.pankqx.tonefer.net.ToneferClient
import com.pankqx.tonefer.service.ConnectionService
import com.pankqx.tonefer.service.Notifications
import com.pankqx.tonefer.util.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToneferApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsStore
        private set
    lateinit var client: ToneferClient
        private set

    /** True while any Tonefer screen is visible (including the invisible share target). */
    @Volatile var inForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, scope)
        client = ToneferClient(this, settings, scope)
        client.onIncoming = ::onIncoming
        client.onQueueDelivered = { n ->
            val laptop = (client.conn.value as? Conn.Online)?.laptop ?: "laptop"
            Notifications.delivered(this, n, laptop)
        }
        // Search non-stop only while Tonefer is on screen. In the background the search winds
        // down by itself, so there's no endless "looking for your laptop" in the shade.
        client.keepSearching = { inForeground }
        client.onIdle = { waiting ->
            ConnectionService.stop(this)
            if (waiting > 0) Notifications.queued(this, waiting, gaveUp = true)
        }
        Notifications.createChannels(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                if (settings.current.pairing != null) {
                    client.start()
                    if (settings.current.background) ConnectionService.start(this@ToneferApp)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                if (!settings.current.background) client.stop()
            }
        })

        // React to the background setting and to unpairing.
        scope.launch {
            settings.state.map { it.background to (it.pairing != null) }.distinctUntilChanged().collect { (bg, paired) ->
                if (bg && paired && inForeground) ConnectionService.start(this@ToneferApp)
                if (!bg || !paired) ConnectionService.stop(this@ToneferApp)
            }
        }
    }

    private suspend fun onIncoming(item: Item) {
        val s = settings.current
        val laptop = (client.conn.value as? Conn.Online)?.laptop ?: s.pairing?.laptop ?: "laptop"
        var savedTo: String? = null

        if (item.isText && s.autoCopyText) {
            withContext(Dispatchers.Main) { Files.copyText(this@ToneferApp, item.text.orEmpty()) }
        }
        val wantSave = (item.isImage || item.isVideo) && s.autoSavePhotos || (!item.isText && !item.isImage && !item.isVideo && s.autoSaveFiles)
        if (wantSave) {
            try {
                val file = client.fileFor(item)
                savedTo = Files.saveToDevice(this, file, item.name ?: file.name, item.mime ?: "application/octet-stream")
            } catch (_: Exception) { }
        }

        if (inForeground) {
            if (s.vibrate) buzz()
        } else if (s.notify) {
            Notifications.incoming(this, item, laptop, s.vibrate, savedTo)
        }
    }

    private fun buzz() {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }
}
