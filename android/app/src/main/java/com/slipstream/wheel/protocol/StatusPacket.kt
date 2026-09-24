package com.slipstream.wheel.protocol

/** Mutable field set of one STATUS packet (PROTOCOL.md section 6). u32 fields are raw bits. */
class StatusPacket {
    var epoch = 0
    var lastSeq = 0
    var echoTUs = 0
    var holdUs = 0
    var accepted = 0
    var missing = 0
    var rumbleStrong = 0
    var rumbleWeak = 0
    var output = 0
    var hubFlags = 0

    /** 0 none, 1 vJoy, 2 Xbox 360. */
    val outputKind: Int get() = output and 0x7F
    val outputError: Boolean get() = (output and Slp.OUTPUT_ERROR_BIT) != 0
}

/**
 * Validates header, exact length and tag of a STATUS packet, then parses it without
 * allocating. One instance per receiving thread.
 */
class StatusDecoder(key: ByteArray) {
    private val auth = PacketAuth(key)

    /** Counts of dropped packets, for diagnostics. Written by the owning thread only. */
    var rejectedHeader = 0L
        private set
    var rejectedTag = 0L
        private set

    fun decode(buf: ByteArray, off: Int, len: Int, out: StatusPacket): Boolean {
        if (len != Slp.STATUS_LEN || !Slp.hasHeader(buf, off, Slp.TYPE_STATUS)) {
            rejectedHeader++
            return false
        }
        if (!auth.verify(buf, off, Slp.STATUS_BODY_LEN)) {
            rejectedTag++
            return false
        }
        out.epoch = Le.u32(buf, off + 4)
        out.lastSeq = Le.u32(buf, off + 8)
        out.echoTUs = Le.u32(buf, off + 12)
        out.holdUs = Le.u32(buf, off + 16)
        out.accepted = Le.u32(buf, off + 20)
        out.missing = Le.u32(buf, off + 24)
        out.rumbleStrong = Le.u16(buf, off + 28)
        out.rumbleWeak = Le.u16(buf, off + 30)
        out.output = buf[off + 32].toInt() and 0xFF
        out.hubFlags = buf[off + 33].toInt() and 0xFF
        return true
    }
}
