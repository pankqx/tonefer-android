package com.pankqx.tonefer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pankqx.tonefer.R
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.ui.MainActivity
import com.pankqx.tonefer.util.Files

object Notifications {
    const val CH_TRANSFERS = "transfers"
    const val CH_TRANSFERS_QUIET = "transfers_quiet"
    const val CH_CONNECTION = "connection"
    const val ID_SERVICE = 1
    const val ID_QUEUE = 2
    private var nextId = 100

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_TRANSFERS, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_transfers_desc)
                enableVibration(true)
            },
            NotificationChannel(CH_TRANSFERS_QUIET, "Transfers (silent)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_transfers_desc)
                enableVibration(false)
                setSound(null, null)
            },
            NotificationChannel(CH_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.channel_connection_desc)
                setShowBadge(false)
            },
        ))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What the background notification shows: the laptop if connected, and anything waiting to go. */
    data class Status(val laptop: String?, val queued: Int, val transfer: String? = null, val percent: Int = 0)

    private fun items(n: Int) = if (n == 1) "1 item" else "$n items"

    fun service(context: Context, status: Status): android.app.Notification {
        val b = NotificationCompat.Builder(context, CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_tonefer)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        when {
            status.transfer != null ->
                b.setContentTitle(status.transfer).setContentText("Keep the hotspot on. You can lock the phone.")
                    .setProgress(100, status.percent, status.percent <= 0)
            status.laptop != null && status.queued > 0 ->
                b.setContentTitle("Sending ${items(status.queued)} to ${status.laptop}")
            status.laptop != null ->
                b.setContentTitle("Connected to ${status.laptop}").setContentText("Notes and photos from the laptop arrive here")
            status.queued > 0 -> {
                val hotspot = PendingIntent.getActivity(context, 7, com.pankqx.tonefer.util.Hotspot.intent(),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.setContentTitle("${items(status.queued)} waiting for your laptop")
                    .setContentText("Sends as soon as the laptop joins your hotspot")
                    .addAction(R.drawable.ic_link, "Turn on hotspot", hotspot)
            }
            else -> b.setContentTitle("Tonefer").setContentText("Ready to send")
        }
        return b.build()
    }

    /**
     * Items waiting for the laptop, with a one-tap hotspot shortcut. Used when the background
     * connection is off, and when the background search ended with items still waiting ([gaveUp]).
     */
    fun queued(context: Context, count: Int, gaveUp: Boolean = false) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val hotspot = PendingIntent.getActivity(context, 7, com.pankqx.tonefer.util.Hotspot.intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CH_TRANSFERS_QUIET)
            .setSmallIcon(R.drawable.ic_stat_tonefer)
            .setContentTitle("${items(count)} waiting for your laptop")
            .let {
                val text = if (gaveUp) "Turn on your hotspot, then open Tonefer or share again to send."
                else "Turn on your hotspot. Tonefer sends it as soon as the laptop connects."
                it.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_link, "Turn on hotspot", hotspot)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
        try { nm.notify(ID_QUEUE, n) } catch (_: SecurityException) { }
    }

    fun delivered(context: Context, count: Int, laptop: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(ID_QUEUE)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CH_TRANSFERS_QUIET)
            .setSmallIcon(R.drawable.ic_stat_tonefer)
            .setContentTitle(if (count == 1) "Sent to $laptop" else "Sent $count items to $laptop")
            .setContentText("Delivered after the laptop connected.")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID_QUEUE, n) } catch (_: SecurityException) { }
    }

    fun incoming(context: Context, item: Item, laptop: String, vibrate: Boolean, savedTo: String?) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val id = nextId++
        val b = NotificationCompat.Builder(context, if (vibrate) CH_TRANSFERS else CH_TRANSFERS_QUIET)
            .setSmallIcon(R.drawable.ic_stat_tonefer)
            .setContentTitle(if (item.isText) "Note from $laptop" else if (item.isImage) "Photo from $laptop" else "File from $laptop")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (item.isText) {
            val text = item.text.orEmpty()
            b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            val copy = PendingIntent.getBroadcast(
                context, id,
                Intent(context, CopyReceiver::class.java).putExtra(CopyReceiver.EXTRA_TEXT, text).putExtra(CopyReceiver.EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(R.drawable.ic_copy, "Copy", copy)
        } else {
            b.setContentText(item.name + (savedTo?.let { "  ·  saved to $it" } ?: ""))
        }
        try { nm.notify(id, b.build()) } catch (_: SecurityException) { }
    }
}

/** "Copy" action on a note notification. */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        Files.copyText(context, text)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
