package com.slipstream.wheel.protocol

/**
 * Wire constants of the Slipstream Link Protocol, version 1 (docs/PROTOCOL.md).
 * Pure Kotlin: nothing in this package may import android.*.
 */
object Slp {
    const val MAGIC0: Byte = 0x53 // 'S'
    const val MAGIC1: Byte = 0x4C // 'L'
    const val VERSION: Byte = 1

    const val TYPE_INPUT: Byte = 1
    const val TYPE_STATUS: Byte = 2
    const val TYPE_BEACON: Byte = 3

    const val HEADER_LEN = 4
    const val TAG_LEN = 8
    const val INPUT_LEN = 52
    const val INPUT_BODY_LEN = INPUT_LEN - TAG_LEN
    const val STATUS_LEN = 44
    const val STATUS_BODY_LEN = STATUS_LEN - TAG_LEN
    const val BEACON_MIN_LEN = 17
    const val BEACON_MAX_NAME = 32

    const val PORT_UDP = 47800
    const val PORT_BEACON = 47801
    const val PORT_TCP = 47802

    /** INPUT flags, byte 40. */
    const val FLAG_PAUSED = 0x01
    const val FLAG_CALIBRATING = 0x02
    const val FLAG_MULTIPATH = 0x04

    /** Buttons bitmask: bits 0..23 are used, 24..31 reserved. */
    const val BUTTONS_USED_MASK = 0x00FF_FFFF
    const val PULSE_CHANNELS = 8

    /** DSCP EF as a TOS byte: the Wi-Fi WMM voice queue. */
    const val TOS_EF = 0xB8

    const val RATE_DEFAULT_HZ = 500
    val RATES_HZ = intArrayOf(250, 500, 1000)
    const val MIN_SPACING_NS = 1_000_000L

    /** STATUS output byte, low 7 bits. */
    const val OUTPUT_NONE = 0
    const val OUTPUT_VJOY = 1
    const val OUTPUT_X360 = 2
    const val OUTPUT_ERROR_BIT = 0x80

    /** True when [buf] at [off] starts with the SLP/1 header of [type]. */
    fun hasHeader(buf: ByteArray, off: Int, type: Byte): Boolean =
        buf[off] == MAGIC0 && buf[off + 1] == MAGIC1 && buf[off + 2] == VERSION && buf[off + 3] == type

    fun writeHeader(buf: ByteArray, off: Int, type: Byte) {
        buf[off] = MAGIC0
        buf[off + 1] = MAGIC1
        buf[off + 2] = VERSION
        buf[off + 3] = type
    }
}

/** Little-endian field access. u32 values travel as the raw 32 bits in an Int. */
object Le {
    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
    }

    fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun i16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

    fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** An u32 carried in an Int, widened to its unsigned value. */
    fun unsigned(v: Int): Long = v.toLong() and 0xFFFF_FFFFL
}

/** Serial-number arithmetic on u32 sequence numbers (PROTOCOL.md section 9.3). */
object Seq {
    /** True when [a] is newer than [b]: d = (a - b) mod 2^32 and 0 < d < 2^31. */
    fun newer(a: Int, b: Int): Boolean = (a - b) > 0
}
