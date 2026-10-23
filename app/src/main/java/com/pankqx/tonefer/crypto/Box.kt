package com.pankqx.tonefer.crypto

import org.json.JSONObject
import java.util.Base64

/** Convenience wrappers matching the laptop's `seal_json` / `open_json`. */
object Box {
    fun seal(key: ByteArray, data: ByteArray): ByteArray = SecretBox.seal(key, data)

    fun open(key: ByteArray, data: ByteArray): ByteArray = SecretBox.open(key, data)

    fun sealJson(key: ByteArray, obj: JSONObject): String =
        Base64.getEncoder().encodeToString(seal(key, obj.toString().toByteArray(Charsets.UTF_8)))

    fun openJson(key: ByteArray, b64: String): JSONObject =
        JSONObject(String(open(key, Base64.getDecoder().decode(b64.trim())), Charsets.UTF_8))
}
