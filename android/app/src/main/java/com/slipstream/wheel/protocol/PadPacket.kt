package com.slipstream.wheel.protocol

/**
 * Mutable field set of one PAD packet (PROTOCOL.md section 12.1). u32 fields hold their raw
 * 32 bits in an Int. The object is reused for every packet, so it is never reallocated.
 */
class PadFrame {
    var epoch = 0
    var seq = 0
    var tUs = 0

    /** Sticks, -32767..32767, +x right, +y up. */
    var lx = 0
    var ly = 0
    var rx = 0
    var ry = 0

    /** Triggers, 0..65535. */
    var l2 = 0
    var r2 = 0

    /** Held canonical buttons, bits 0..17. */
    var buttons = 0

    /** One 4-bit wrapping tap counter per canonical button, 0..15. */
    val taps = ByteArray(Slp.PAD_BUTTONS)

    var flags = 0
    var rtt100us = 0

    /** Touchpad fingers: coordinates 0..65535, id byte = bit7 active | bits 0..6 tracking id. */
    var touch0X = 0
    var touch0Y = 0
    var touch1X = 0
    var touch1Y = 0
    var touch0Id = 0
    var touch1Id = 0

    /** Angular rate in 1/16 dps and acceleration in 1/4096 g, controller frame. */
    var gyroX = 0
    var gyroY = 0
    var gyroZ = 0
    var accelX = 0
    var accelY = 0
    var accelZ = 0
}

/**
 * The 18 tap counters on the wire: button 2k in the low nibble of byte k, button 2k + 1 in
 * the high nibble (PROTOCOL.md 12.1), and the hub's delta rule (12.4 rule 3).
 */
object TapNibbles {
    fun pack(taps: ByteArray, out: ByteArray, off: Int) {
        for (k in 0 until Slp.PAD_TAP_BYTES) {
            val lo = taps[2 * k].toInt() and 0xF
            val hi = taps[2 * k + 1].toInt() and 0xF
            out[off + k] = (lo or (hi shl 4)).toByte()
        }
    }

    fun unpack(src: ByteArray, off: Int, taps: ByteArray) {
        for (k in 0 until Slp.PAD_TAP_BYTES) {
            val b = src[off + k].toInt()
            taps[2 * k] = (b and 0xF).toByte()
            taps[2 * k + 1] = ((b ushr 4) and 0xF).toByte()
        }
    }

    /** Taps between two readings of one counter: (new - old) mod 16 when it is in 1..7, else 0. */
    fun delta(new: Int, old: Int): Int {
        val d = (new - old) and 0xF
        return if (d in 1..7) d else 0
    }
}

/**
 * Builds PAD packets into one reused 76 byte buffer with one reused Mac.
 * Owned by a single thread (the sender).
 */
class PadPacketWriter(key: ByteArray) {
    /** The packet built by the last [write]. Overwritten by the next call. */
    val buffer = ByteArray(Slp.PAD_LEN)
    private val auth = PacketAuth(key)

    fun write(f: PadFrame): ByteArray {
        val b = buffer
        Slp.writeHeader(b, 0, Slp.TYPE_PAD)
        Le.putU32(b, 4, f.epoch)
        Le.putU32(b, 8, f.seq)
        Le.putU32(b, 12, f.tUs)
        // Sticks are clamped to -32767..32767: -32768 is never sent (12.3).
        Le.putU16(b, 16, stick(f.lx))
        Le.putU16(b, 18, stick(f.ly))
        Le.putU16(b, 20, stick(f.rx))
        Le.putU16(b, 22, stick(f.ry))
        Le.putU16(b, 24, u16(f.l2))
        Le.putU16(b, 26, u16(f.r2))
        Le.putU32(b, 28, f.buttons and Slp.PAD_BUTTONS_MASK)
        TapNibbles.pack(f.taps, b, 32)
        b[41] = (f.flags and Slp.PAD_FLAGS_MASK).toByte()
        Le.putU16(b, 42, u16(f.rtt100us))
        Le.putU16(b, 44, u16(f.touch0X))
        Le.putU16(b, 46, u16(f.touch0Y))
        Le.putU16(b, 48, u16(f.touch1X))
        Le.putU16(b, 50, u16(f.touch1Y))
        b[52] = f.touch0Id.toByte()
        b[53] = f.touch1Id.toByte()
        Le.putU16(b, 54, i16(f.gyroX))
        Le.putU16(b, 56, i16(f.gyroY))
        Le.putU16(b, 58, i16(f.gyroZ))
        Le.putU16(b, 60, i16(f.accelX))
        Le.putU16(b, 62, i16(f.accelY))
        Le.putU16(b, 64, i16(f.accelZ))
        Le.putU16(b, 66, 0)
        auth.sign(b, Slp.PAD_BODY_LEN)
        return b
    }

    private fun stick(v: Int): Int = v.coerceIn(-32767, 32767)
    private fun i16(v: Int): Int = v.coerceIn(-32768, 32767)
    private fun u16(v: Int): Int = v.coerceIn(0, 0xFFFF)
}

/**
 * Validates and parses PAD packets exactly as the hub must (header, exact length, tag). The
 * phone never receives PAD; this exists so the encoder can be checked end to end in tests
 * and by debugging tools.
 */
class PadPacketReader(key: ByteArray) {
    private val auth = PacketAuth(key)

    fun read(buf: ByteArray, off: Int, len: Int, out: PadFrame): Boolean {
        if (len != Slp.PAD_LEN) return false
        if (!Slp.hasHeader(buf, off, Slp.TYPE_PAD)) return false
        if (!auth.verify(buf, off, Slp.PAD_BODY_LEN)) return false
        out.epoch = Le.u32(buf, off + 4)
        out.seq = Le.u32(buf, off + 8)
        out.tUs = Le.u32(buf, off + 12)
        out.lx = Le.i16(buf, off + 16)
        out.ly = Le.i16(buf, off + 18)
        out.rx = Le.i16(buf, off + 20)
        out.ry = Le.i16(buf, off + 22)
        out.l2 = Le.u16(buf, off + 24)
        out.r2 = Le.u16(buf, off + 26)
        out.buttons = Le.u32(buf, off + 28)
        TapNibbles.unpack(buf, off + 32, out.taps)
        out.flags = buf[off + 41].toInt() and 0xFF
        out.rtt100us = Le.u16(buf, off + 42)
        out.touch0X = Le.u16(buf, off + 44)
        out.touch0Y = Le.u16(buf, off + 46)
        out.touch1X = Le.u16(buf, off + 48)
        out.touch1Y = Le.u16(buf, off + 50)
        out.touch0Id = buf[off + 52].toInt() and 0xFF
        out.touch1Id = buf[off + 53].toInt() and 0xFF
        out.gyroX = Le.i16(buf, off + 54)
        out.gyroY = Le.i16(buf, off + 56)
        out.gyroZ = Le.i16(buf, off + 58)
        out.accelX = Le.i16(buf, off + 60)
        out.accelY = Le.i16(buf, off + 62)
        out.accelZ = Le.i16(buf, off + 64)
        return true
    }
}
