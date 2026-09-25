package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.PacketSource
import com.slipstream.wheel.link.Transport
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.Slp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The 300 ms hold between two packet source switches is kept on the wire: it counts from the
 * first packet of the new type, not from the moment the switch was decided. The two differ when
 * the switch is decided right after a packet, because the first packet of the new type then
 * waits out the 1 ms minimum spacing. Timed from the decision, the PAD packets covered about
 * 299 ms (PadLinkTest once saw 298.8 ms).
 */
class PadSourceHoldTest {
    private val key = PairingCode.deriveKey("SLIPSTREAMTEST22")

    /**
     * Records every packet's type and t_us. On the sender thread itself it asks for PAD right after
     * an INPUT packet (so the first PAD waits for the spacing), then asks back for INPUT as soon as
     * that first PAD packet is out.
     */
    private class SwitchingRecorder : Transport {
        override val slot: Int = LinkEngine.SLOT_WIFI
        override val label: String = "switching-recorder"
        val types = IntArray(4000)
        val tUs = IntArray(4000)
        val seqs = IntArray(4000)
        @Volatile var count = 0
        @Volatile var engine: LinkEngine? = null
        @Volatile var armed = false
        private var askedBack = false

        override fun start() = Unit
        override fun stop() = Unit
        override fun canSend(): Boolean = true

        override fun send(packet: ByteArray, len: Int) {
            val i = count
            if (i >= types.size) return
            types[i] = packet[3].toInt()
            seqs[i] = Le.u32(packet, 8)
            tUs[i] = Le.u32(packet, 12)
            count = i + 1
            val e = engine ?: return
            if (armed && types[i] == Slp.TYPE_INPUT.toInt()) {
                armed = false
                e.setSource(PacketSource.PAD)
            } else if (!askedBack && types[i] == Slp.TYPE_PAD.toInt()) {
                askedBack = true
                e.setSource(PacketSource.INPUT)
            }
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(2)
        }
        return cond()
    }

    @Test
    fun padPacketsSpanTheWholeHoldEvenWhenTheSwitchWaitsForTheSpacing() {
        val pad = PadState()
        pad.setFlag(Slp.FLAG_PAUSED, false)
        val state = ControllerState()
        state.setFlag(Slp.FLAG_PAUSED, false)
        val rec = SwitchingRecorder()
        // 1000 Hz: the idle repeat wakes the sender every millisecond, so the switch back happens
        // within about 1 ms of the end of the hold.
        val config = LinkConfig(key = key, host = null, useWifi = false, useUsb = false, rateHz = 1000)
        val engine = LinkEngine(state, pad, config, null, listOf(rec), PacketSource.INPUT)
        rec.engine = engine
        engine.start()
        try {
            assertTrue(waitFor(1000) { rec.count >= 20 })
            rec.armed = true
            assertTrue("back to INPUT after the hold", waitFor(3000) { engine.source == PacketSource.INPUT && rec.types.take(rec.count).contains(Slp.TYPE_PAD.toInt()) })
            val backAt = rec.count
            assertTrue(waitFor(1000) { rec.count >= backAt + 20 })
        } finally {
            engine.stop()
        }
        val n = rec.count
        for (i in 1 until n) assertEquals("one sequence across both types", rec.seqs[i - 1] + 1, rec.seqs[i])
        val firstPad = (0 until n).first { rec.types[it] == Slp.TYPE_PAD.toInt() }
        val backToInput = (firstPad until n).first { rec.types[it] == Slp.TYPE_INPUT.toInt() }
        for (i in firstPad until backToInput) assertEquals(Slp.TYPE_PAD.toInt(), rec.types[i])
        val waitedUs = rec.tUs[firstPad] - rec.tUs[firstPad - 1]
        assertTrue("the first PAD waited out the 1 ms spacing after the last INPUT: $waitedUs us", waitedUs >= 1000)
        val heldUs = rec.tUs[backToInput] - rec.tUs[firstPad]
        assertTrue("PAD packets span at least 300 ms on the wire, got $heldUs us", heldUs >= 300_000)
        assertTrue("and the switch back is not late: $heldUs us", heldUs < 320_000)
    }
}
