package com.slipstream.wheel.protocol

/**
 * Mutable field set of one INPUT packet (PROTOCOL.md section 5). u32 fields hold their raw
 * 32 bits in an Int. The object is reused for every packet, so it is never reallocated.
 */
class InputFrame {
    var epoch = 0
    var seq = 0
    var tUs = 0
    var steer = 0
    var throttle = 0
    var brake = 0
    var clutch = 0
    var handbrake = 0
    var aux = 0
    var buttons = 0
    val pulses = ByteArray(Slp.PULSE_CHANNELS)
    var flags = 0
    var rtt100us = 0

    fun copyFrom(o: InputFrame) {
        epoch = o.epoch; seq = o.seq; tUs = o.tUs
        steer = o.steer; throttle = o.throttle; brake = o.brake; clutch = o.clutch
        handbrake = o.handbrake; aux = o.aux; buttons = o.buttons
        System.arraycopy(o.pulses, 0, pulses, 0, Slp.PULSE_CHANNELS)
        flags = o.flags; rtt100us = o.rtt100us
    }
}

/**
 * Builds INPUT packets into one reused 52 byte buffer with one reused Mac.
 * Owned by a single thread (the sender).
 */
class InputPacketWriter(key: ByteArray) {
    /** The packet built by the last [write]. Overwritten by the next call. */
    val buffer = ByteArray(Slp.INPUT_LEN)
    private val auth = PacketAuth(key)

    fun write(f: InputFrame): ByteArray {
        val b = buffer
        Slp.writeHeader(b, 0, Slp.TYPE_INPUT)
        Le.putU32(b, 4, f.epoch)
        Le.putU32(b, 8, f.seq)
        Le.putU32(b, 12, f.tUs)
        // -32768 is never sent.
        Le.putU16(b, 16, f.steer.coerceIn(-32767, 32767))
        Le.putU16(b, 18, u16(f.throttle))
        Le.putU16(b, 20, u16(f.brake))
        Le.putU16(b, 22, u16(f.clutch))
        Le.putU16(b, 24, u16(f.handbrake))
        Le.putU16(b, 26, u16(f.aux))
        Le.putU32(b, 28, f.buttons)
        System.arraycopy(f.pulses, 0, b, 32, Slp.PULSE_CHANNELS)
        b[40] = f.flags.toByte()
        b[41] = 0
        Le.putU16(b, 42, u16(f.rtt100us))
        auth.sign(b, Slp.INPUT_BODY_LEN)
        return b
    }

    private fun u16(v: Int): Int = v.coerceIn(0, 0xFFFF)
}

/**
 * Validates and parses INPUT packets. The phone never receives INPUT; this exists so the
 * encoder can be checked end to end in tests and by debugging tools.
 */
class InputPacketReader(key: ByteArray) {
    private val auth = PacketAuth(key)

    fun read(buf: ByteArray, off: Int, len: Int, out: InputFrame): Boolean {
        if (len != Slp.INPUT_LEN) return false
        if (!Slp.hasHeader(buf, off, Slp.TYPE_INPUT)) return false
        if (!auth.verify(buf, off, Slp.INPUT_BODY_LEN)) return false
        out.epoch = Le.u32(buf, off + 4)
        out.seq = Le.u32(buf, off + 8)
        out.tUs = Le.u32(buf, off + 12)
        out.steer = Le.i16(buf, off + 16)
        out.throttle = Le.u16(buf, off + 18)
        out.brake = Le.u16(buf, off + 20)
        out.clutch = Le.u16(buf, off + 22)
        out.handbrake = Le.u16(buf, off + 24)
        out.aux = Le.u16(buf, off + 26)
        out.buttons = Le.u32(buf, off + 28)
        System.arraycopy(buf, off + 32, out.pulses, 0, Slp.PULSE_CHANNELS)
        out.flags = buf[off + 40].toInt() and 0xFF
        out.rtt100us = Le.u16(buf, off + 42)
        return true
    }
}
