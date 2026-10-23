package com.pankqx.tonefer.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * NaCl {@code crypto_secretbox} (XSalsa20 + Poly1305), byte-compatible with
 * libsodium, PyNaCl and tweetnacl.
 *
 * <p>Wire format used by Tonefer: {@code nonce(24) || tag(16) || ciphertext}.
 *
 * <p>Self-contained on purpose: no native libraries and no dependencies, so the
 * same code runs on Android and in plain JVM unit tests.
 */
public final class SecretBox {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;
    public static final int TAG_BYTES = 16;
    public static final int OVERHEAD = NONCE_BYTES + TAG_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int[] SIGMA = {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574};

    private SecretBox() {}

    /** Encrypts {@code message} with a fresh random nonce. */
    public static byte[] seal(byte[] key, byte[] message) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return seal(key, nonce, message);
    }

    /** Encrypts with the given nonce. Only for tests; never reuse a nonce. */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] message) {
        checkKey(key);
        if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("nonce must be 24 bytes");
        byte[] out = new byte[OVERHEAD + message.length];
        System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);

        byte[] subKey = hsalsa20(key, nonce);
        byte[] polyKey = new byte[32];
        xorStream(subKey, nonce, message, 0, message.length, out, OVERHEAD, polyKey);
        byte[] tag = poly1305(polyKey, out, OVERHEAD, message.length);
        System.arraycopy(tag, 0, out, NONCE_BYTES, TAG_BYTES);
        return out;
    }

    /** Decrypts and authenticates. Throws if the key is wrong or the data was altered. */
    public static byte[] open(byte[] key, byte[] boxed) throws GeneralSecurityException {
        checkKey(key);
        if (boxed == null || boxed.length < OVERHEAD) throw new GeneralSecurityException("ciphertext too short");
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(boxed, 0, nonce, 0, NONCE_BYTES);
        int len = boxed.length - OVERHEAD;

        byte[] subKey = hsalsa20(key, nonce);
        byte[] polyKey = firstBlock(subKey, nonce);
        byte[] expected = poly1305(polyKey, boxed, OVERHEAD, len);
        byte[] actual = new byte[TAG_BYTES];
        System.arraycopy(boxed, NONCE_BYTES, actual, 0, TAG_BYTES);
        if (!MessageDigest.isEqual(expected, actual)) throw new GeneralSecurityException("authentication failed");

        byte[] out = new byte[len];
        xorStream(subKey, nonce, boxed, OVERHEAD, len, out, 0, null);
        return out;
    }

    private static void checkKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) throw new IllegalArgumentException("key must be 32 bytes");
    }

    // ------------------------------------------------------------------ Salsa20

    private static int rotl(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static void st32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void rounds(int[] x) {
        for (int i = 0; i < 20; i += 2) {
            x[4] ^= rotl(x[0] + x[12], 7);   x[8] ^= rotl(x[4] + x[0], 9);
            x[12] ^= rotl(x[8] + x[4], 13);  x[0] ^= rotl(x[12] + x[8], 18);
            x[9] ^= rotl(x[5] + x[1], 7);    x[13] ^= rotl(x[9] + x[5], 9);
            x[1] ^= rotl(x[13] + x[9], 13);  x[5] ^= rotl(x[1] + x[13], 18);
            x[14] ^= rotl(x[10] + x[6], 7);  x[2] ^= rotl(x[14] + x[10], 9);
            x[6] ^= rotl(x[2] + x[14], 13);  x[10] ^= rotl(x[6] + x[2], 18);
            x[3] ^= rotl(x[15] + x[11], 7);  x[7] ^= rotl(x[3] + x[15], 9);
            x[11] ^= rotl(x[7] + x[3], 13);  x[15] ^= rotl(x[11] + x[7], 18);
            x[1] ^= rotl(x[0] + x[3], 7);    x[2] ^= rotl(x[1] + x[0], 9);
            x[3] ^= rotl(x[2] + x[1], 13);   x[0] ^= rotl(x[3] + x[2], 18);
            x[6] ^= rotl(x[5] + x[4], 7);    x[7] ^= rotl(x[6] + x[5], 9);
            x[4] ^= rotl(x[7] + x[6], 13);   x[5] ^= rotl(x[4] + x[7], 18);
            x[11] ^= rotl(x[10] + x[9], 7);  x[8] ^= rotl(x[11] + x[10], 9);
            x[9] ^= rotl(x[8] + x[11], 13);  x[10] ^= rotl(x[9] + x[8], 18);
            x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9);
            x[14] ^= rotl(x[13] + x[12], 13); x[15] ^= rotl(x[14] + x[13], 18);
        }
    }

    /** HSalsa20: derives the XSalsa20 sub-key from the key and the first 16 nonce bytes. */
    static byte[] hsalsa20(byte[] key, byte[] nonce) {
        int[] x = new int[16];
        x[0] = SIGMA[0]; x[5] = SIGMA[1]; x[10] = SIGMA[2]; x[15] = SIGMA[3];
        for (int i = 0; i < 4; i++) {
            x[1 + i] = le32(key, 4 * i);
            x[11 + i] = le32(key, 16 + 4 * i);
            x[6 + i] = le32(nonce, 4 * i);
        }
        rounds(x);
        byte[] out = new byte[32];
        int[] idx = {0, 5, 10, 15, 6, 7, 8, 9};
        for (int i = 0; i < 8; i++) st32(out, 4 * i, x[idx[i]]);
        return out;
    }

    /** One 64-byte Salsa20 block for (subKey, nonce[16..24], counter). */
    private static void block(byte[] subKey, byte[] nonce, long counter, int[] in, int[] x, byte[] out) {
        in[0] = SIGMA[0]; in[5] = SIGMA[1]; in[10] = SIGMA[2]; in[15] = SIGMA[3];
        for (int i = 0; i < 4; i++) {
            in[1 + i] = le32(subKey, 4 * i);
            in[11 + i] = le32(subKey, 16 + 4 * i);
        }
        in[6] = le32(nonce, 16);
        in[7] = le32(nonce, 20);
        in[8] = (int) counter;
        in[9] = (int) (counter >>> 32);
        System.arraycopy(in, 0, x, 0, 16);
        rounds(x);
        for (int i = 0; i < 16; i++) st32(out, 4 * i, x[i] + in[i]);
    }

    private static byte[] firstBlock(byte[] subKey, byte[] nonce) {
        byte[] ks = new byte[64];
        block(subKey, nonce, 0, new int[16], new int[16], ks);
        byte[] polyKey = new byte[32];
        System.arraycopy(ks, 0, polyKey, 0, 32);
        return polyKey;
    }

    /**
     * XORs {@code len} bytes of {@code src} with the XSalsa20 stream, skipping the first 32
     * stream bytes (they become the Poly1305 key, copied into {@code polyKeyOut} if non-null).
     */
    private static void xorStream(byte[] subKey, byte[] nonce, byte[] src, int srcOff, int len,
                                  byte[] dst, int dstOff, byte[] polyKeyOut) {
        int[] in = new int[16];
        int[] x = new int[16];
        byte[] ks = new byte[64];
        long counter = 0;
        block(subKey, nonce, counter++, in, x, ks);
        if (polyKeyOut != null) System.arraycopy(ks, 0, polyKeyOut, 0, 32);
        int pos = 0;
        int ksPos = 32;
        while (pos < len) {
            if (ksPos == 64) {
                block(subKey, nonce, counter++, in, x, ks);
                ksPos = 0;
            }
            int n = Math.min(64 - ksPos, len - pos);
            for (int i = 0; i < n; i++) {
                dst[dstOff + pos + i] = (byte) (src[srcOff + pos + i] ^ ks[ksPos + i]);
            }
            pos += n;
            ksPos += n;
        }
    }

    // ------------------------------------------------------------------ Poly1305 (donna-32)

    private static long u32(byte[] b, int o) {
        return le32(b, o) & 0xffffffffL;
    }

    static byte[] poly1305(byte[] key, byte[] m, int off, int len) {
        final long M26 = 0x3ffffff;
        long r0 = u32(key, 0) & 0x3ffffff;
        long r1 = (u32(key, 3) >>> 2) & 0x3ffff03;
        long r2 = (u32(key, 6) >>> 4) & 0x3ffc0ff;
        long r3 = (u32(key, 9) >>> 6) & 0x3f03fff;
        long r4 = (u32(key, 12) >>> 8) & 0x00fffff;
        long s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
        long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;

        byte[] blk = new byte[17];
        int pos = 0;
        while (pos < len) {
            int n = Math.min(16, len - pos);
            long hibit;
            if (n == 16) {
                System.arraycopy(m, off + pos, blk, 0, 16);
                hibit = 1L << 24;
            } else {
                java.util.Arrays.fill(blk, (byte) 0);
                System.arraycopy(m, off + pos, blk, 0, n);
                blk[n] = 1;
                hibit = 0;
            }
            h0 += u32(blk, 0) & M26;
            h1 += (u32(blk, 3) >>> 2) & M26;
            h2 += (u32(blk, 6) >>> 4) & M26;
            h3 += (u32(blk, 9) >>> 6) & M26;
            h4 += (u32(blk, 12) >>> 8) | hibit;

            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

            long c = d0 >>> 26; h0 = d0 & M26;
            d1 += c; c = d1 >>> 26; h1 = d1 & M26;
            d2 += c; c = d2 >>> 26; h2 = d2 & M26;
            d3 += c; c = d3 >>> 26; h3 = d3 & M26;
            d4 += c; c = d4 >>> 26; h4 = d4 & M26;
            h0 += c * 5; c = h0 >>> 26; h0 &= M26;
            h1 += c;
            pos += n;
        }

        long c = h1 >>> 26; h1 &= M26;
        h2 += c; c = h2 >>> 26; h2 &= M26;
        h3 += c; c = h3 >>> 26; h3 &= M26;
        h4 += c; c = h4 >>> 26; h4 &= M26;
        h0 += c * 5; c = h0 >>> 26; h0 &= M26;
        h1 += c;

        long g0 = h0 + 5; c = g0 >>> 26; g0 &= M26;
        long g1 = h1 + c; c = g1 >>> 26; g1 &= M26;
        long g2 = h2 + c; c = g2 >>> 26; g2 &= M26;
        long g3 = h3 + c; c = g3 >>> 26; g3 &= M26;
        long g4 = h4 + c - (1L << 26);

        long useH = g4 >> 63;          // -1 if h < p (keep h), 0 otherwise (use g = h - p)
        long useG = ~useH;
        h0 = (h0 & useH) | (g0 & useG);
        h1 = (h1 & useH) | (g1 & useG);
        h2 = (h2 & useH) | (g2 & useG);
        h3 = (h3 & useH) | (g3 & useG);
        h4 = (h4 & useH) | (g4 & M26 & useG);

        long f0 = (h0 | (h1 << 26)) & 0xffffffffL;
        long f1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
        long f2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL;
        long f3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;

        long f = f0 + u32(key, 16);            f0 = f & 0xffffffffL;
        f = f1 + u32(key, 20) + (f >>> 32);    f1 = f & 0xffffffffL;
        f = f2 + u32(key, 24) + (f >>> 32);    f2 = f & 0xffffffffL;
        f = f3 + u32(key, 28) + (f >>> 32);    f3 = f & 0xffffffffL;

        byte[] tag = new byte[16];
        st32(tag, 0, (int) f0);
        st32(tag, 4, (int) f1);
        st32(tag, 8, (int) f2);
        st32(tag, 12, (int) f3);
        return tag;
    }
}
