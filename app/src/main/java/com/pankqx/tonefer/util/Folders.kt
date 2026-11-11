package com.pankqx.tonefer.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.pankqx.tonefer.net.Outgoing

/** Reads a folder picked with the system folder picker, keeping its structure for the zip. */
object Folders {

    class Picked(val name: String, val files: List<Outgoing>, val bytes: Long)

    /** Lists every file under [tree] (sub-folders included). Paths start with the folder's own name. */
    fun walk(context: Context, tree: Uri): Picked {
        val cr = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var rootName = "Folder"
        try {
            cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { rootName = it } }
        } catch (_: Exception) { }
        val out = ArrayList<Outgoing>()
        var bytes = 0L
        val todo = ArrayDeque<Pair<String, String>>()   // document id, path prefix
        todo.add(rootId to rootName)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        while (todo.isNotEmpty()) {
            val (id, prefix) = todo.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            try {
                cr.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: "application/octet-stream"
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        if (mime == Document.MIME_TYPE_DIR) {
                            todo.add(childId to "$prefix/$name")
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                            if (size > 0) bytes += size
                            out.add(Outgoing(name, mime, size, "$prefix/$name") {
                                cr.openInputStream(uri) ?: throw IllegalStateException("Can't read $name")
                            })
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        out.sortBy { it.path }
        return Picked(rootName, out, bytes)
    }
}
