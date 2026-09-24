package com.slipstream.wheel.input

/**
 * Eight wrapping u8 press counters packed into one Long, channel 0 in the lowest byte.
 * Channel 0 is shift up, 1 is shift down, 2..7 are user actions (PROTOCOL.md section 5).
 */
object PulseCounters {
    const val CHANNELS = 8
    const val SHIFT_UP = 0
    const val SHIFT_DOWN = 1

    fun get(packed: Long, channel: Int): Int = ((packed ushr (channel * 8)) and 0xFF).toInt()

    /** Adds one press on [channel]. 255 wraps to 0 without touching the other channels. */
    fun increment(packed: Long, channel: Int): Long {
        require(channel in 0 until CHANNELS)
        val shift = channel * 8
        val next = ((packed ushr shift) + 1) and 0xFF
        return (packed and (0xFFL shl shift).inv()) or (next shl shift)
    }

    /** Writes the eight counters into out[off, off + 8) in channel order. */
    fun unpack(packed: Long, out: ByteArray, off: Int = 0) {
        for (ch in 0 until CHANNELS) out[off + ch] = (packed ushr (ch * 8)).toByte()
    }

    /**
     * Presses between two readings of one counter, as the hub counts them (PROTOCOL.md 9.4):
     * (new - old) mod 256 when it is in 1..127, otherwise 0.
     */
    fun delta(new: Int, old: Int): Int {
        val d = (new - old) and 0xFF
        return if (d in 1..127) d else 0
    }
}
