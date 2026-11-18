package com.pankqx.tonefer.p2p

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pankqx.tonefer.R
import com.pankqx.tonefer.ToneferApp
import com.pankqx.tonefer.service.Notifications
import com.pankqx.tonefer.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps Phone to phone reachable while the screen is off or another app is open. */
class HostService : LifecycleService() {

    private var collecting = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as ToneferApp
        if (intent?.action == ACTION_STOP) {
            app.host.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, ID, build(0, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!collecting) {
            collecting = true
            lifecycleScope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                combine(app.host.state, app.host.transfers) { s, t ->
                    val first = t.firstOrNull()
                    Triple(s.guests, first?.let { "${it.label} ${it.name}" }, first?.let { (it.progress * 100).toInt() } ?: 0)
                }.distinctUntilChanged().collect { (guests, transfer, pct) ->
                    nm.notify(ID, build(guests, transfer, pct))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(guests: Int, transfer: String?, percent: Int = 0): android.app.Notification {
        val open = PendingIntent.getActivity(this, 11,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_SCREEN, "host"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 12, Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, Notifications.CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_tonefer)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_close, "Stop", stop)
        when {
            transfer != null -> b.setContentTitle(transfer).setContentText("Keep the hotspot on. You can lock the phone.")
                .setProgress(100, percent, percent <= 0)
            guests > 0 -> b.setContentTitle("Phone to phone · ${if (guests == 1) "1 phone" else "$guests phones"} connected")
                .setContentText("Notes, photos and files go straight between the phones")
            else -> b.setContentTitle("Phone to phone is on").setContentText("Waiting for the other phone to scan the code")
        }
        return b.build()
    }

    companion object {
        private const val ID = 3
        private const val ACTION_STOP = "com.pankqx.tonefer.HOST_STOP"

        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, HostService::class.java)) } catch (_: Exception) { }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostService::class.java))
        }
    }
}
