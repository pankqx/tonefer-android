package com.pankqx.tonefer.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.net.Conn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the connection to the laptop alive while Tonefer is in the background,
 * so notes and photos arrive (and auto-save) without opening the app.
 * Controlled by Settings → "Stay connected in background".
 *
 * It runs only around real use: opening the app or sharing to it starts it, and the client
 * stops it again once the laptop has been gone for a while (see ToneferClient.keepSearching).
 */
class ConnectionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as ToneferApp
        val status = status(app.client.conn.value, app.client.queued.value)
        posted = status
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE,
                Notifications.service(this, status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (_: Exception) {
            // Not allowed to start in the background right now; the app will retry when opened.
            stopSelf()
            return START_NOT_STICKY
        }
        app.client.start()
        if (!collecting) {
            collecting = true
            lifecycleScope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                // Only connected / waiting-to-send changes are worth updating the notification for.
                // Connecting and searching blips are left out, so it never flickers or re-alerts.
                combine(app.client.conn, app.client.queued) { c, q -> status(c, q) }
                    .distinctUntilChanged()
                    .collect {
                        if (it != posted) {
                            posted = it
                            nm.notify(Notifications.ID_SERVICE, Notifications.service(this@ConnectionService, it))
                        }
                    }
            }
        }
        // Not sticky: if Android stops the service it stays stopped until Tonefer is opened or used.
        return START_NOT_STICKY
    }

    private var collecting = false
    private var posted: Notifications.Status? = null

    private fun status(c: Conn, queued: Int) = Notifications.Status((c as? Conn.Online)?.laptop, queued)

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (_: Exception) {
                // Background start restrictions: ignored, retried next time the app is in front.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
