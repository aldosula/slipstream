package com.slipstream.wheel.protocol

import java.security.MessageDigest

/**
 * Pairing code handling (PROTOCOL.md section 2). The code is 16 characters of the RFC 4648
 * base32 alphabet. It never crosses the network: only the derived fingerprint does.
 */
object PairingCode {
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    const val LENGTH = 16
    const val RAW_LEN = 10

    private val KEY_LABEL = "slipstream/v1/key".toByteArray(Charsets.US_ASCII)
    private val FP_LABEL = "slipstream/v1/fp".toByteArray(Charsets.US_ASCII)

    /**
     * Uppercase, drop '-', space and tab, map 0 to O, 1 to I, 8 to B. Any other character
     * outside the alphabet, or a length other than 16, makes the code invalid (null).
     */
    fun normalize(text: String): String? {
        val upper = text.uppercase()
        val out = StringBuilder(LENGTH)
        for (raw in upper) {
            if (raw == '-' || raw == ' ' || raw == '\t') continue
            val ch = when (raw) {
                '0' -> 'O'
                '1' -> 'I'
                '8' -> 'B'
                else -> raw
            }
            if (ALPHABET.indexOf(ch) < 0) return null
            out.append(ch)
        }
        return if (out.length == LENGTH) out.toString() else null
    }

    /** RFC 4648 base32 decode of a normalized 16 character code into 10 bytes. */
    fun decode(code: String): ByteArray {
        require(code.length == LENGTH) { "pairing code must be $LENGTH characters" }
        val out = ByteArray(RAW_LEN)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in code) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "character outside the base32 alphabet" }
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc ushr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out
    }

    /** K = SHA256("slipstream/v1/key" || raw), 32 bytes, the HMAC key. */
    fun deriveKey(code: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(KEY_LABEL)
        md.update(decode(code))
        return md.digest()
    }

    /** fingerprint = SHA256("slipstream/v1/fp" || K)[0..8). Public, carried by beacons. */
    fun fingerprint(key: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(FP_LABEL)
        md.update(key)
        return md.digest().copyOf(8)
    }

    /** Grouped display form: ABCD-EFGH-IJKL-MNOP. */
    fun display(code: String): String = code.chunked(4).joinToString("-")
}

/** A validated pairing: the normalized code, the HMAC key and the public fingerprint. */
class PairingKey private constructor(
    val code: String,
    val key: ByteArray,
    val fingerprint: ByteArray,
) {
    val display: String get() = PairingCode.display(code)

    /** Short fingerprint for the interface, e.g. "e3ca 5404". */
    val fingerprintShort: String get() = hex(fingerprint, 0, 2) + " " + hex(fingerprint, 2, 2)

    /** Constant-time comparison against a fingerprint seen on the network. */
    fun matches(other: ByteArray?): Boolean =
        other != null && other.size == fingerprint.size && MessageDigest.isEqual(fingerprint, other)

    companion object {
        /** Parses user input or a QR value. Returns null when the code is not valid. */
        fun fromInput(input: String): PairingKey? {
            val code = PairingCode.normalize(input) ?: return null
            val key = PairingCode.deriveKey(code)
            return PairingKey(code, key, PairingCode.fingerprint(key))
        }

        fun hex(b: ByteArray, off: Int = 0, len: Int = b.size): String {
            val digits = "0123456789abcdef"
            val sb = StringBuilder(len * 2)
            for (i in off until off + len) {
                val v = b[i].toInt() and 0xFF
                sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
            }
            return sb.toString()
        }
    }
}
