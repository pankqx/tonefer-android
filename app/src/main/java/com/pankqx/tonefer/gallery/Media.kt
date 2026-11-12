package com.pankqx.tonefer.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size

/** One photo or video on the phone. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val time: Long,        // milliseconds
    val size: Long,
    val video: Boolean,
    val durationMs: Long,
    val folder: String,
)

object Media {

    /** Every photo and video the system knows about, newest first. Nothing is opened, so it is fast. */
    fun load(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_TAKEN, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DURATION, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        )
        val where = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        try {
            context.contentResolver.query(files, cols, where, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val video = c.getInt(5) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val taken = if (c.isNull(2)) 0L else c.getLong(2)
                    val modified = (if (c.isNull(3)) 0L else c.getLong(3)) * 1000
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out.add(MediaItem(
                        id = id, uri = ContentUris.withAppendedId(base, id),
                        name = c.getString(1) ?: "file",
                        time = if (taken > 0) taken else modified,
                        size = if (c.isNull(4)) 0L else c.getLong(4),
                        video = video,
                        durationMs = if (c.isNull(6)) 0L else c.getLong(6),
                        folder = c.getString(7) ?: "",
                    ))
                }
            }
        } catch (_: SecurityException) {
            // Permission was taken away while the screen was open.
        }
        out.sortByDescending { it.time }
        return out
    }

    /** Thumbnails kept in memory, capped at a sixth of the app's heap. */
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 6 / 1024).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    fun cached(id: Long): Bitmap? = cache.get(id)

    /** Android's own thumbnail for the item (it keeps them on disk, so this is quick after the first time). */
    fun thumbnail(context: Context, item: MediaItem, px: Int = 320): Bitmap? {
        cache.get(item.id)?.let { return it }
        return try {
            context.contentResolver.loadThumbnail(item.uri, Size(px, px), null).also { cache.put(item.id, it) }
        } catch (_: Exception) { null }
    }

    fun forget(id: Long) { cache.remove(id) }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
