package com.slipstream.wheel.input

import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.Slp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Wakes the one consumer thread (the link sender or the Bluetooth report thread) when the
 * controller state changes. Lock free: an atomic flag plus LockSupport.unpark.
 */
class ChangeSignal {
    private val pending = AtomicBoolean(false)
    private val consumer = AtomicReference<Thread?>(null)

    fun attach(thread: Thread?) {
        consumer.set(thread)
    }

    /**
     * Detaches [thread] only if it is still the consumer. A sender that is shutting down must
     * not wipe out the registration of the sender that replaced it, or the new one would
     * only wake on its idle timeout and send-on-change would be lost.
     */
    fun detach(thread: Thread) {
        consumer.compareAndSet(thread, null)
    }

    fun raise() {
        pending.set(true)
        val t = consumer.get()
        if (t != null) LockSupport.unpark(t)
    }

    fun isPending(): Boolean = pending.get()

    /** The thread woken by [raise], for diagnostics and tests. */
    val consumerThread: Thread? get() = consumer.get()

    /** Clears the flag. Call before taking the snapshot so a change made during it is kept. */
    fun consume(): Boolean = pending.getAndSet(false)
}

/**
 * The complete controller state, written by the sensor thread (steering) and the main
 * thread (touch, keys) and read by the sender. Every field group lives in one atomic word
 * and is updated with a compare-and-set loop, so writers never block, readers never see a
 * torn field, and [snapshot] allocates nothing.
 */
class ControllerState {
    val signal = ChangeSignal()

    private val steer = AtomicInteger(0)

    /** throttle | brake << 16 | clutch << 32 | handbrake << 48, each u16. */
    private val pedals = AtomicLong(0)

    /** Held buttons, bits 0..23. */
    private val buttons = AtomicInteger(0)

    /** Eight u8 pulse counters, see [PulseCounters]. */
    private val pulses = AtomicLong(0)

    /** PAUSED and CALIBRATING. MULTIPATH is added by the link per packet. */
    private val flags = AtomicInteger(Slp.FLAG_PAUSED)

    val steerValue: Int get() = steer.get()
    val throttle: Int get() = pedal(SLOT_THROTTLE)
    val brake: Int get() = pedal(SLOT_BRAKE)
    val handbrake: Int get() = pedal(SLOT_HANDBRAKE)
    val heldButtons: Int get() = buttons.get()
    val flagBits: Int get() = flags.get()

    fun setSteer(value: Int) {
        val v = value.coerceIn(-32767, 32767)
        if (steer.getAndSet(v) != v) signal.raise()
    }

    fun setThrottle(value: Int) = setPedal(SLOT_THROTTLE, value)
    fun setBrake(value: Int) = setPedal(SLOT_BRAKE, value)
    fun setClutch(value: Int) = setPedal(SLOT_CLUTCH, value)
    fun setHandbrake(value: Int) = setPedal(SLOT_HANDBRAKE, value)

    fun setButton(bit: Int, held: Boolean) {
        if (bit !in 0..23) return
        val mask = 1 shl bit
        while (true) {
            val old = buttons.get()
            val next = if (held) old or mask else old and mask.inv()
            if (next == old) return
            if (buttons.compareAndSet(old, next)) {
                signal.raise()
                return
            }
        }
    }

    /** One press on a pulse channel (0 shift up, 1 shift down, 2..7 user). */
    fun pulse(channel: Int) {
        while (true) {
            val old = pulses.get()
            if (pulses.compareAndSet(old, PulseCounters.increment(old, channel))) {
                signal.raise()
                return
            }
        }
    }

    fun pulseCounter(channel: Int): Int = PulseCounters.get(pulses.get(), channel)

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

    /** Pedals and held buttons to rest. Steering and pulse counters are kept. */
    fun releaseAll() {
        val hadPedals = pedals.getAndSet(0) != 0L
        val hadButtons = buttons.getAndSet(0) != 0
        if (hadPedals || hadButtons) signal.raise()
    }

    /** Copies the state into [out] (steer, axes, buttons, pulses, flags). No allocation. */
    fun snapshot(out: InputFrame) {
        out.steer = steer.get()
        val p = pedals.get()
        out.throttle = (p and 0xFFFF).toInt()
        out.brake = ((p ushr 16) and 0xFFFF).toInt()
        out.clutch = ((p ushr 32) and 0xFFFF).toInt()
        out.handbrake = ((p ushr 48) and 0xFFFF).toInt()
        out.aux = 0
        out.buttons = buttons.get() and Slp.BUTTONS_USED_MASK
        PulseCounters.unpack(pulses.get(), out.pulses)
        out.flags = flags.get() and (Slp.FLAG_PAUSED or Slp.FLAG_CALIBRATING)
    }

    private fun pedal(slot: Int): Int = ((pedals.get() ushr (slot * 16)) and 0xFFFF).toInt()

    private fun setPedal(slot: Int, value: Int) {
        val shift = slot * 16
        val v = value.coerceIn(0, 0xFFFF).toLong()
        val mask = (0xFFFFL shl shift).inv()
        while (true) {
            val old = pedals.get()
            if (((old ushr shift) and 0xFFFF) == v) return
            if (pedals.compareAndSet(old, (old and mask) or (v shl shift))) {
                signal.raise()
                return
            }
        }
    }

    private companion object {
        const val SLOT_THROTTLE = 0
        const val SLOT_BRAKE = 1
        const val SLOT_CLUTCH = 2
        const val SLOT_HANDBRAKE = 3
    }
}
