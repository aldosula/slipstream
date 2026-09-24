package com.slipstream.wheel.hid

import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.protocol.Le

/**
 * Bluetooth HID gamepad report (PROTOCOL.md section 10, Bluetooth HID mode). Pure Kotlin.
 *
 * Report ID 1: five 16 bit axes (X steer, Y throttle, Z brake, Rx clutch, Ry handbrake),
 * each 0..65535, then 32 buttons numbered as in the vJoy table: button j + 1 is pulse
 * channel j (0..7), button i + 9 is held bit i (0..23).
 */
object HidReport {
    const val REPORT_ID = 1

    /** Report payload without the ID byte (the Bluetooth stack prepends it): 5 * 2 + 4. */
    const val PAYLOAD_LEN = 14

    val DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01, //       Usage Page (Generic Desktop)
        0x09, 0x05, //       Usage (Game Pad)
        0xA1, 0x01, //       Collection (Application)
        0x85, REPORT_ID, //    Report ID (1)
        0x05, 0x01, //         Usage Page (Generic Desktop)
        0x09, 0x30, //         Usage (X), steer
        0x09, 0x31, //         Usage (Y), throttle
        0x09, 0x32, //         Usage (Z), brake
        0x09, 0x33, //         Usage (Rx), clutch
        0x09, 0x34, //         Usage (Ry), handbrake
        0x15, 0x00, //         Logical Minimum (0)
        0x27, 0xFF, 0xFF, 0x00, 0x00, // Logical Maximum (65535), 4 byte item: 2 bytes would read as -1
        0x75, 0x10, //         Report Size (16)
        0x95, 0x05, //         Report Count (5)
        0x81, 0x02, //         Input (Data, Variable, Absolute)
        0x05, 0x09, //         Usage Page (Button)
        0x19, 0x01, //         Usage Minimum (Button 1)
        0x29, 0x20, //         Usage Maximum (Button 32)
        0x15, 0x00, //         Logical Minimum (0)
        0x25, 0x01, //         Logical Maximum (1)
        0x75, 0x01, //         Report Size (1)
        0x95, 0x20, //         Report Count (32)
        0x81, 0x02, //         Input (Data, Variable, Absolute)
        0xC0, //             End Collection
    )

    /** INPUT steer (-32767..32767) to the HID X axis, 0..65534 with center 32767. */
    fun steer(steer: Int): Int = steer.coerceIn(-32767, 32767) + 32767

    /** Pulse bits (bit j = channel j pressed now) and held bits to the 32 button bitmask. */
    fun buttons(pulseBits: Int, held: Int): Int =
        (pulseBits and 0xFF) or ((held and 0x00FF_FFFF) shl PulseCounters.CHANNELS)

    /** Writes one report payload into [out] (at least [PAYLOAD_LEN] bytes). */
    fun write(
        out: ByteArray,
        steer: Int,
        throttle: Int,
        brake: Int,
        clutch: Int,
        handbrake: Int,
        buttons: Int,
    ) {
        Le.putU16(out, 0, steer(steer))
        Le.putU16(out, 2, throttle.coerceIn(0, 0xFFFF))
        Le.putU16(out, 4, brake.coerceIn(0, 0xFFFF))
        Le.putU16(out, 6, clutch.coerceIn(0, 0xFFFF))
        Le.putU16(out, 8, handbrake.coerceIn(0, 0xFFFF))
        Le.putU32(out, 10, buttons)
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}

/**
 * Renders pulse counters into button presses on the phone, as the hub does (PROTOCOL.md
 * section 9.4): each increment becomes one press held for [pressMs], then a [gapMs] release
 * before the next queued press on that channel. The first reading is the baseline and emits
 * nothing. Pure: time is passed in.
 */
class PulseRenderer(private val pressMs: Long = 60, private val gapMs: Long = 40) {
    private val last = IntArray(PulseCounters.CHANNELS)
    private val queued = IntArray(PulseCounters.CHANNELS)
    private val phase = IntArray(PulseCounters.CHANNELS)
    private val until = LongArray(PulseCounters.CHANNELS)
    private var baseline = false

    fun reset() {
        baseline = false
        queued.fill(0)
        phase.fill(IDLE)
    }

    /** Feeds the current counters. Returns the pressed-channel bitmask at [nowMs]. */
    fun update(counters: ByteArray, nowMs: Long): Int {
        var bits = 0
        for (ch in 0 until PulseCounters.CHANNELS) {
            val c = counters[ch].toInt() and 0xFF
            if (baseline) queued[ch] += PulseCounters.delta(c, last[ch])
            last[ch] = c
            advance(ch, nowMs)
            if (phase[ch] == DOWN) bits = bits or (1 shl ch)
        }
        baseline = true
        return bits
    }

    /** The earliest time the output can change without a new counter value, or MAX_VALUE. */
    fun nextChangeMs(): Long {
        var next = Long.MAX_VALUE
        for (ch in 0 until PulseCounters.CHANNELS) {
            if (phase[ch] != IDLE && until[ch] < next) next = until[ch]
        }
        return next
    }

    private fun advance(ch: Int, nowMs: Long) {
        while (true) {
            when (phase[ch]) {
                DOWN -> if (nowMs >= until[ch]) {
                    phase[ch] = GAP
                    until[ch] += gapMs
                } else return
                GAP -> if (nowMs >= until[ch]) phase[ch] = IDLE else return
                else -> if (queued[ch] > 0) {
                    queued[ch]--
                    phase[ch] = DOWN
                    until[ch] = nowMs + pressMs
                    return
                } else return
            }
        }
    }

    private companion object {
        const val IDLE = 0
        const val DOWN = 1
        const val GAP = 2
    }
}
