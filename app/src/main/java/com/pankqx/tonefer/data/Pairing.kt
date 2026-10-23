package com.pankqx.tonefer.data

import com.pankqx.tonefer.crypto.SecretBox
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * The shared secret plus where the laptop was last seen.
 *
 * Pairing links look like `http://10.42.0.15:8765/#k=<base64url key>`; the key travels in
 * the fragment, which is never sent over the network.
 */
data class Pairing(
    val keyB64: String,
    val host: String,
    val port: Int,
    val laptop: String,
) {
    val key: ByteArray by lazy { decodeKey(keyB64) ?: ByteArray(0) }

    /** Access token the laptop expects: first 64 hex chars of SHA-512(key || "tonefer-auth"). */
    val token: String by lazy { tokenFor(key) }

    val baseUrl: String get() = "http://$host:$port"

    override fun toString() = "Pairing(host=$host, port=$port, laptop=$laptop)" // never log the key

    companion object {
        const val DEFAULT_PORT = 8765

        fun fromStored(keyB64: String, host: String, port: Int, laptop: String) =
            Pairing(keyB64, host, port, laptop)

        fun tokenFor(key: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-512")
            md.update(key)
            md.update("tonefer-auth".toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 64)
        }

        fun decodeKey(b64: String): ByteArray? = try {
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            Base64.getUrlDecoder().decode(padded).takeIf { it.size == SecretBox.KEY_BYTES }
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Parses a scanned or pasted pairing link. Returns null if it isn't a Tonefer link. */
        fun parse(raw: String): Pairing? {
            val text = raw.trim()
            val keyB64 = Regex("""[#&?]k=([A-Za-z0-9_-]{40,})""").find(text)?.groupValues?.get(1) ?: return null
            if (decodeKey(keyB64) == null) return null
            val withScheme = if (text.contains("://")) text else "http://$text"
            return try {
                val uri = URI(withScheme.substringBefore('#'))
                val host = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else DEFAULT_PORT
                Pairing(keyB64, host, port, laptop = "")
            } catch (_: Exception) {
                null
            }
        }
    }
}
