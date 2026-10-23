package com.pankqx.tonefer.data

import org.json.JSONObject

/** One entry in the shared history. Mirrors the laptop's item format (see tonefer/docs/PROTOCOL.md). */
data class Item(
    val id: String,
    val kind: String,          // "text" | "file"
    val origin: String,        // "phone" | "laptop"
    val ts: Long,
    val text: String? = null,
    val name: String? = null,
    val size: Long = 0,
    val mime: String? = null,
) {
    val isText get() = kind == "text"
    val isImage get() = kind == "file" && mime?.startsWith("image/") == true && mime != "image/svg+xml"
    val isVideo get() = kind == "file" && mime?.startsWith("video/") == true
    val fromPhone get() = origin == "phone"
    val extension get() = name?.substringAfterLast('.', "")?.take(4)?.uppercase().orEmpty().ifEmpty { "FILE" }

    companion object {
        fun from(o: JSONObject) = Item(
            id = o.getString("id"),
            kind = o.optString("kind", "text"),
            origin = o.optString("origin", "laptop"),
            ts = o.optLong("ts", System.currentTimeMillis()),
            text = if (o.has("text")) o.getString("text") else null,
            name = if (o.has("name")) o.getString("name") else null,
            size = o.optLong("size", 0),
            mime = if (o.has("mime")) o.getString("mime") else null,
        )
    }
}
