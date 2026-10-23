package com.pankqx.tonefer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Saving to the shared gallery / Downloads, opening and sharing received files. */
object Files {

    /** Copies a received file into Pictures/Tonefer, Movies/Tonefer or Download/Tonefer. Returns a user-facing folder name. */
    fun saveToDevice(context: Context, file: File, name: String, mime: String): String {
        val (collection, folder) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_PICTURES}/Tonefer"
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/Tonefer"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_DOWNLOADS}/Tonefer"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(collection, values) ?: throw IllegalStateException("Couldn't create $name")
        try {
            cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return folder
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun open(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun share(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyText(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Tonefer", text))
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v < 10) "%.1f %s".format(v, units[i]) else "%.0f %s".format(v, units[i])
    }
}
