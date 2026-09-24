package com.slipstream.wheel.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * BEACON (PROTOCOL.md section 7): unauthenticated, 17 + n bytes, broadcast once per second.
 * The hub address is the datagram's source IP, which the caller supplies.
 */
class Beacon(
    val udpPort: Int,
    val tcpPort: Int,
    val fingerprint: ByteArray,
    val name: String,
) {
    /** Constant-time fingerprint comparison against the key the phone holds. */
    fun matches(fp: ByteArray?): Boolean =
        fp != null && fp.size == fingerprint.size && MessageDigest.isEqual(fingerprint, fp)

    companion object {
        /** Returns null for any packet whose magic, version, type or exact length is wrong. */
        fun decode(buf: ByteArray, off: Int, len: Int): Beacon? {
            if (len < Slp.BEACON_MIN_LEN) return null
            if (!Slp.hasHeader(buf, off, Slp.TYPE_BEACON)) return null
            val n = buf[off + 16].toInt() and 0xFF
            if (n > Slp.BEACON_MAX_NAME || len != Slp.BEACON_MIN_LEN + n) return null
            val udp = Le.u16(buf, off + 4)
            val tcp = Le.u16(buf, off + 6)
            if (udp == 0 || tcp == 0) return null
            val name = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buf, off + 17, n))
                    .toString()
            } catch (e: CharacterCodingException) {
                return null
            }
            return Beacon(udp, tcp, buf.copyOfRange(off + 8, off + 16), name)
        }
    }
}
