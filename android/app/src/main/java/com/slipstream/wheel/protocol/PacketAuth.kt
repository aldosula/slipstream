package com.slipstream.wheel.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * tag(body) = HMAC-SHA256(K, body)[0..8), compared in constant time.
 *
 * One instance per thread: [Mac] is not thread safe. The Mac and every scratch buffer are
 * created once, so signing or verifying a packet allocates nothing in this class.
 */
class PacketAuth(key: ByteArray) {
    private val mac: Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    private val full = ByteArray(32)
    private val expected = ByteArray(Slp.TAG_LEN)
    private val received = ByteArray(Slp.TAG_LEN)

    /** Writes the tag of buf[0, bodyLen) into buf[bodyLen, bodyLen + 8). */
    fun sign(buf: ByteArray, bodyLen: Int) {
        mac.update(buf, 0, bodyLen)
        mac.doFinal(full, 0)
        System.arraycopy(full, 0, buf, bodyLen, Slp.TAG_LEN)
    }

    /** True when buf[off + bodyLen, +8) is the tag of buf[off, off + bodyLen). */
    fun verify(buf: ByteArray, off: Int, bodyLen: Int): Boolean {
        mac.update(buf, off, bodyLen)
        mac.doFinal(full, 0)
        System.arraycopy(full, 0, expected, 0, Slp.TAG_LEN)
        System.arraycopy(buf, off + bodyLen, received, 0, Slp.TAG_LEN)
        return MessageDigest.isEqual(expected, received)
    }
}
