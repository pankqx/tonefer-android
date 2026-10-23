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
import com.pankqx.tonefer.net.Conn
import com.pankqx.tonefer.ui.MainActivity
import com.pankqx.tonefer.util.Files

object Notifications {
    const val CH_TRANSFERS = "transfers"
    const val CH_TRANSFERS_QUIET = "transfers_quiet"
    const val CH_CONNECTION = "connection"
    const val ID_SERVICE = 1
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

    fun service(context: Context, conn: Conn) = NotificationCompat.Builder(context, CH_CONNECTION)
        .setSmallIcon(R.drawable.ic_stat_tonefer)
        .setContentTitle(when (conn) {
            is Conn.Online -> "Connected to ${conn.laptop}"
            Conn.Searching -> "Looking for your laptop…"
            is Conn.Connecting -> "Connecting…"
            Conn.Unpaired -> "Not paired"
            else -> "Waiting for your laptop"
        })
        .setContentText("Tap to open Tonefer")
        .setContentIntent(openApp(context))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

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
