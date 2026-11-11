package com.pankqx.tonefer.crypto

import java.nio.ByteBuffer

/**
 * Framing for big transfers (protocol v2). Every chunk is sealed on its own with a header
 * that names its transfer and position, so chunks can't be swapped, replayed or cut short:
 *
 *     tid (16 ASCII bytes) | index (uint32, big-endian) | flags (1 = last chunk)
 */
object Chunks {
    const val HEADER = 21

    fun header(tid: String, index: Int, last: Boolean): ByteArray {
        val out = ByteArray(HEADER)
        val id = tid.toByteArray(Charsets.US_ASCII)
        System.arraycopy(id, 0, out, 0, minOf(16, id.size))
        ByteBuffer.wrap(out, 16, 4).putInt(index)
        out[20] = if (last) 1 else 0
        return out
    }

    /** Seals header + the first [length] bytes of [data]. */
    fun seal(key: ByteArray, tid: String, index: Int, last: Boolean, data: ByteArray, length: Int): ByteArray {
        val plain = ByteArray(HEADER + length)
        System.arraycopy(header(tid, index, last), 0, plain, 0, HEADER)
        System.arraycopy(data, 0, plain, HEADER, length)
        return SecretBox.seal(key, plain)
    }

    class Chunk(val tid: String, val index: Int, val last: Boolean, val plain: ByteArray) {
        /** Offset and length of the file bytes inside [plain]. */
        val offset get() = HEADER
        val length get() = plain.size - HEADER
    }

    fun open(key: ByteArray, sealed: ByteArray): Chunk {
        val plain = SecretBox.open(key, sealed)
        require(plain.size >= HEADER) { "chunk too short" }
        return Chunk(
            String(plain, 0, 16, Charsets.US_ASCII),
            ByteBuffer.wrap(plain, 16, 4).int,
            (plain[20].toInt() and 1) == 1,
            plain,
        )
    }
}
