package com.slipstream.wheel.pad

import com.slipstream.wheel.input.ChangeSignal
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.Slp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The complete gamepad state, written by the main thread (touch, keys) and the motion
 * thread (gyro, accelerometer, gyro aim), read by the link sender. Like ControllerState,
 * every field group lives in one atomic word updated with a compare-and-set loop: writers
 * never block, readers never see a torn field, and [snapshot] allocates nothing.
 *
 * Every change a player makes raises [signal], so the sender puts it on the wire at once
 * (send-on-change, PROTOCOL.md 12.3). Raw motion does not: the idle repeat (2 ms at 500 Hz)
 * carries it, which keeps the packet rate from doubling at the sensor rate.
 */
class PadState(val signal: ChangeSignal = ChangeSignal()) {

    /** Touch sticks, four i16: lx | ly << 16 | rx << 32 | ry << 48. */
    private val sticks = AtomicLong(0)

    /** Gyro aim added to the right stick at snapshot time, two i16: x | y << 16. */
    private val aim = AtomicInteger(0)

    /** l2 | r2 << 16, each u16. */
    private val triggers = AtomicInteger(0)

    /** Held bits and tap counters, see [TapCounters]: buttons 0..8 and 9..17. */
    private val buttonsLo = AtomicLong(0)
    private val buttonsHi = AtomicLong(0)

    /** Touchpad fingers: x | y << 16 | id byte << 32. */
    private val touch0 = AtomicLong(0)
    private val touch1 = AtomicLong(0)

    /** Three i16 each: x | y << 16 | z << 32. */
    private val gyro = AtomicLong(0)
    private val accel = AtomicLong(0)

    /** PAUSED, MOTION and STYLE_PS. MULTIPATH is added by the link per packet. */
    private val flags = AtomicInteger(Slp.FLAG_PAUSED)

    /** A finger is on the right stick, for gyro aim in "while touching" mode. */
    @Volatile var rightStickTouched = false

    val heldButtons: Int get() = TapCounters.heldBits(buttonsLo.get(), buttonsHi.get())
    val flagBits: Int get() = flags.get()

    fun tapCounter(bit: Int): Int {
        val w = (if (TapCounters.wordOf(bit) == 0) buttonsLo else buttonsHi).get()
        return TapCounters.tap(w, TapCounters.indexOf(bit))
    }

    fun stickX(stick: Int): Int = i16(sticks.get(), stick * 32)
    fun stickY(stick: Int): Int = i16(sticks.get(), stick * 32 + 16)
    fun trigger(side: Int): Int = (triggers.get() ushr (side * 16)) and 0xFFFF

    /** [stick] 0 left, 1 right. Values are clamped to -32767..32767, +y up. */
    fun setStick(stick: Int, x: Int, y: Int) {
        // Anything but 0 or 1 would shift into the other stick (shift counts wrap mod 64).
        if (stick != 0 && stick != 1) return
        val shift = stick * 32
        val v = ((clampAxis(x).toLong() and 0xFFFF) or ((clampAxis(y).toLong() and 0xFFFF) shl 16)) shl shift
        val mask = (0xFFFF_FFFFL shl shift).inv()
        while (true) {
            val old = sticks.get()
            val next = (old and mask) or v
            if (next == old) return
            if (sticks.compareAndSet(old, next)) {
                signal.raise()
                return
            }
        }
    }

    /** Gyro aim deflection, packed as StickMath.pack (x | y << 16). */
    fun setGyroAim(packed: Int) {
        if (aim.getAndSet(packed) != packed) signal.raise()
    }

    /** [side] 0 left (L2 / LT), 1 right (R2 / RT), value 0..65535. */
    fun setTrigger(side: Int, value: Int) {
        if (side != 0 && side != 1) return
        val shift = side * 16
        val v = value.coerceIn(0, 0xFFFF) shl shift
        val mask = (0xFFFF shl shift).inv()
        while (true) {
            val old = triggers.get()
            val next = (old and mask) or v
            if (next == old) return
            if (triggers.compareAndSet(old, next)) {
                signal.raise()
                return
            }
        }
    }

    /**
     * Holds or releases canonical button [bit]. Press-down increments its tap counter in the
     * same atomic step that sets the held bit; holding an already held button does nothing.
     */
    fun setButton(bit: Int, held: Boolean) {
        if (!PadButton.isValid(bit)) return
        val word = if (TapCounters.wordOf(bit) == 0) buttonsLo else buttonsHi
        val i = TapCounters.indexOf(bit)
        while (true) {
            val old = word.get()
            val next = if (held) TapCounters.press(old, i) else TapCounters.release(old, i)
            if (next == old) return
            if (word.compareAndSet(old, next)) {
                signal.raise()
                return
            }
        }
    }

    /** Touchpad finger [slot] (0 or 1). [id] is the 7-bit tracking id; x and y are 0..65535. */
    fun setTouch(slot: Int, active: Boolean, id: Int, x: Int, y: Int) {
        if (slot != 0 && slot != 1) return
        val idByte = (if (active) 0x80 else 0) or (id and 0x7F)
        val v = (x.coerceIn(0, 0xFFFF).toLong()) or (y.coerceIn(0, 0xFFFF).toLong() shl 16) or (idByte.toLong() shl 32)
        val cell = if (slot == 0) touch0 else touch1
        if (cell.getAndSet(v) != v) signal.raise()
    }

    /** Angular rate in 1/16 dps, controller frame. Motion thread; does not raise the signal. */
    fun setGyro(x: Int, y: Int, z: Int) = gyro.set(pack3(x, y, z))

    /** Acceleration in 1/4096 g, controller frame. Motion thread; does not raise the signal. */
    fun setAccel(x: Int, y: Int, z: Int) = accel.set(pack3(x, y, z))

    fun setFlag(flag: Int, on: Boolean) {
        while (true) {
            val old = flags.get()
            val next = if (on) old or flag else old and flag.inv()
            if (next == old) return
            if (flags.compareAndSet(old, next)) {
                signal.raise()
                return
            }
        }
    }

    /**
     * Everything the player holds back to rest: sticks centred, triggers 0, held bits
     * cleared, touchpad fingers inactive. Tap counters and tracking ids are kept, so the hub
     * never counts a press that did not happen.
     */
    fun releaseAll() {
        var changed = sticks.getAndSet(0) != 0L
        if (aim.getAndSet(0) != 0) changed = true
        if (triggers.getAndSet(0) != 0) changed = true
        if (clearHeld(buttonsLo)) changed = true
        if (clearHeld(buttonsHi)) changed = true
        if (clearActive(touch0)) changed = true
        if (clearActive(touch1)) changed = true
        if (changed) signal.raise()
    }

    /** Copies the state into [out]: every field except epoch, seq, t_us and rtt. No allocation. */
    fun snapshot(out: PadFrame) {
        val s = sticks.get()
        val a = aim.get()
        out.lx = i16(s, 0)
        out.ly = i16(s, 16)
        out.rx = clampAxis(i16(s, 32) + (a shl 16 shr 16))
        out.ry = clampAxis(i16(s, 48) + (a shr 16))
        val t = triggers.get()
        out.l2 = t and 0xFFFF
        out.r2 = (t ushr 16) and 0xFFFF
        val lo = buttonsLo.get()
        val hi = buttonsHi.get()
        out.buttons = TapCounters.heldBits(lo, hi)
        TapCounters.unpackTaps(lo, hi, out.taps)
        val t0 = touch0.get()
        val t1 = touch1.get()
        out.touch0X = (t0 and 0xFFFF).toInt()
        out.touch0Y = ((t0 ushr 16) and 0xFFFF).toInt()
        out.touch0Id = ((t0 ushr 32) and 0xFF).toInt()
        out.touch1X = (t1 and 0xFFFF).toInt()
        out.touch1Y = ((t1 ushr 16) and 0xFFFF).toInt()
        out.touch1Id = ((t1 ushr 32) and 0xFF).toInt()
        val f = flags.get() and (Slp.FLAG_PAUSED or Slp.FLAG_MOTION or Slp.FLAG_STYLE_PS)
        out.flags = f
        if (f and Slp.FLAG_MOTION != 0) {
            val g = gyro.get()
            val c = accel.get()
            out.gyroX = i16(g, 0)
            out.gyroY = i16(g, 16)
            out.gyroZ = i16(g, 32)
            out.accelX = i16(c, 0)
            out.accelY = i16(c, 16)
            out.accelZ = i16(c, 32)
        } else {
            out.gyroX = 0; out.gyroY = 0; out.gyroZ = 0
            out.accelX = 0; out.accelY = 0; out.accelZ = 0
        }
    }

    private fun clearHeld(word: AtomicLong): Boolean {
        while (true) {
            val old = word.get()
            val next = TapCounters.releaseAll(old)
            if (next == old) return false
            if (word.compareAndSet(old, next)) return true
        }
    }

    private fun clearActive(cell: AtomicLong): Boolean {
        while (true) {
            val old = cell.get()
            val next = old and (0x80L shl 32).inv()
            if (next == old) return false
            if (cell.compareAndSet(old, next)) return true
        }
    }

    private companion object {
        fun clampAxis(v: Int): Int = v.coerceIn(-32767, 32767)

        fun i16(packed: Long, shift: Int): Int = ((packed ushr shift) and 0xFFFF).toInt().toShort().toInt()

        fun pack3(x: Int, y: Int, z: Int): Long =
            (x.coerceIn(-32768, 32767).toLong() and 0xFFFF) or
                ((y.coerceIn(-32768, 32767).toLong() and 0xFFFF) shl 16) or
                ((z.coerceIn(-32768, 32767).toLong() and 0xFFFF) shl 32)
    }
}

/**
 * Hold counts per canonical button for the main thread, so a button bound twice (a touch
 * control and a volume key, or two fingers on one face button) is released only when the
 * last source lets go. Only the first hold is a press on the wire.
 */
class ButtonHolds(private val state: PadState) {
    private val count = IntArray(PadButton.COUNT)

    /**
     * How many times [clear] ran. A source that keeps its own "I hold this" flag (a volume
     * key) records it at press time and releases only if no clear happened since, so it never
     * releases a hold that belongs to another source.
     */
    var clears = 0
        private set

    /** Returns true when this was a new press (the button was not held before). */
    fun press(bit: Int): Boolean {
        if (!PadButton.isValid(bit)) return false
        val first = count[bit] == 0
        count[bit]++
        if (first) state.setButton(bit, true)
        return first
    }

    fun release(bit: Int) {
        if (!PadButton.isValid(bit) || count[bit] == 0) return
        count[bit]--
        if (count[bit] == 0) state.setButton(bit, false)
    }

    fun isHeld(bit: Int): Boolean = PadButton.isValid(bit) && count[bit] > 0

    /** Forgets every hold. The caller releases the state itself (PadState.releaseAll). */
    fun clear() {
        count.fill(0)
        clears++
    }
}

/**
 * A physical key (volume up or down) mapped to a canonical button in controller mode. Its
 * key-up releases the button only when its own key-down made a hold that is still counted:
 * a key-down that was ignored (pause menu open, screen paused) or a hold wiped by
 * [ButtonHolds.clear] (every finger released) must not take away a finger's hold on the same
 * button. Main thread only.
 */
class KeyHold(private val holds: ButtonHolds) {
    private var bit = -1
    private var clearsAtPress = -1

    val isHolding: Boolean get() = bit >= 0 && clearsAtPress == holds.clears

    /** Key down for [mapped]. Returns true when it was a new press (for the haptic tick). */
    fun down(mapped: Int): Boolean {
        if (isHolding || !PadButton.isValid(mapped)) return false
        bit = mapped
        clearsAtPress = holds.clears
        return holds.press(mapped)
    }

    /** Key up: releases only this key's own, still counted, hold. */
    fun up() {
        if (isHolding) holds.release(bit)
        bit = -1
        clearsAtPress = -1
    }
}
