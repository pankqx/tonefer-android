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
import kotlinx.coroutines.launch

/**
 * Keeps the connection to the laptop alive while Tonefer is in the background,
 * so notes and photos arrive (and auto-save) without opening the app.
 * Controlled by Settings → "Stay connected in background".
 */
class ConnectionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as ToneferApp
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE,
                Notifications.service(this, app.client.conn.value),
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
                app.client.conn.collect { nm.notify(Notifications.ID_SERVICE, Notifications.service(this@ConnectionService, it)) }
            }
        }
        return START_STICKY
    }

    private var collecting = false

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
