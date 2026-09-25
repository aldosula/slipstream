package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.PacketSource
import com.slipstream.wheel.link.Transport
import com.slipstream.wheel.pad.ButtonHolds
import com.slipstream.wheel.pad.GyroAim
import com.slipstream.wheel.pad.KeyHold
import com.slipstream.wheel.pad.MotionMath
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.pad.PadTouchRules
import com.slipstream.wheel.pad.StickMath
import com.slipstream.wheel.pad.TriggerMath
import com.slipstream.wheel.pad.layout.ControlKind
import com.slipstream.wheel.pad.layout.DefaultLayouts
import com.slipstream.wheel.pad.layout.LayoutCodec
import com.slipstream.wheel.pad.layout.PadGeometry
import com.slipstream.wheel.pad.layout.Box
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.TapNibbles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Touch decisions of the play surface that live outside the View. */
class PadTouchRulesTest {
    @Test
    fun pauseRingKeepsItsCoreButLetsADrawnControlKeepTheRestOfItsArea() {
        val hit = 22f
        val core = 12f
        // Not over a control: the whole 44 dp circle starts a pause hold.
        assertTrue(PadTouchRules.pauseRingTakes(0f, hit, core, overControl = false))
        assertTrue(PadTouchRules.pauseRingTakes(21.9f, hit, core, overControl = false))
        assertFalse(PadTouchRules.pauseRingTakes(22.1f, hit, core, overControl = false))
        // Over a drawn control (the PlayStation touchpad reaches up into the ring's area):
        // only the drawn ring and its 4 dp border, so a touchpad swipe there is not stolen.
        assertTrue(PadTouchRules.pauseRingTakes(11.9f, hit, core, overControl = true))
        assertFalse(PadTouchRules.pauseRingTakes(12.1f, hit, core, overControl = true))
        assertFalse(PadTouchRules.pauseRingTakes(20f, hit, core, overControl = true))
    }

    @Test
    fun theTouchpadTapOfTheDefaultPlayStationLayoutOverlapsTheRingOnlyOutsideItsCore() {
        // Reference screen, dp: ring at (441, 22.2), touchpad top edge at 17.3.
        val ringX = DefaultLayouts.PAUSE_CX * 882f
        val ringY = DefaultLayouts.PAUSE_CY * 411f
        val pad = DefaultLayouts.playStation().control("touchpad")!!
        val box = PadGeometry.rect(pad, 882f, 411f, 1f, 1f, Box())
        assertTrue("the spec's ring area overlaps the touchpad", box.t < ringY + 22f)
        // A touch on the touchpad, 3 dp below its top edge and 14 dp right of the ring's centre.
        val x = ringX + 14f
        val y = box.t + 3f
        val d = kotlin.math.hypot(x - ringX, y - ringY)
        assertTrue(d < 22f)
        assertTrue(box.contains(x, y))
        assertFalse("the touchpad keeps it", PadTouchRules.pauseRingTakes(d, 22f, 12f, box.contains(x, y)))
    }

    @Test
    fun cancelledPointersAreNeverATouchpadClick() {
        assertTrue(PadTouchRules.touchpadTapIsClick(120, 0.01f, canceled = false))
        assertFalse("palm rejection (FLAG_CANCELED)", PadTouchRules.touchpadTapIsClick(120, 0.01f, canceled = true))
        assertFalse("too long", PadTouchRules.touchpadTapIsClick(201, 0.01f, canceled = false))
        assertFalse("moved", PadTouchRules.touchpadTapIsClick(120, 0.04f, canceled = false))
        assertTrue(PadTouchRules.isCanceled(0x20))
        assertTrue(PadTouchRules.isCanceled(0x20 or 0x01))
        assertFalse(PadTouchRules.isCanceled(0x01))
        assertEquals(40L, PadTouchRules.clickHoldMs(5))
        assertEquals(150L, PadTouchRules.clickHoldMs(150))
    }

    @Test
    fun aSecondTouchpadTapWhileTheFirstClickIsHeldIsItsOwnTapAtTheHub() {
        // The surface releases and presses again in one go. Whatever snapshot the sender
        // takes, the hub (PROTOCOL.md 12.4 rule 3, tap_schedule vectors) makes two clicks.
        val state = PadState()
        val holds = ButtonHolds(state)
        val first = PadFrame()
        val second = PadFrame()
        val b = PadButton.TOUCHPAD
        holds.press(b)
        state.snapshot(first)
        holds.release(b)
        holds.press(b)
        state.snapshot(second)
        val d = TapNibbles.delta(second.taps[b].toInt(), first.taps[b].toInt())
        assertEquals("the second tap is counted", 1, d)
        assertTrue((second.buttons ushr b) and 1 != 0)
        val v = TestVectors.json.getJSONArray("tap_schedule")
        var checked = false
        for (i in 0 until v.length()) {
            val e = v.getJSONObject(i)
            if (e.getBoolean("output_down") && e.getBoolean("held") && e.getInt("d") == d) {
                // Release for gap_ms, then follow the held bit: a second press.
                assertTrue(e.getBoolean("gap_first"))
                assertEquals(0, e.getInt("replay_taps"))
                checked = true
            }
        }
        assertTrue(checked)
        // Merging the two taps (the old behaviour) leaves the counter where it was: one click.
        val merged = PadFrame()
        state.snapshot(merged)
        holds.press(b) // already held: not a press
        val after = PadFrame()
        state.snapshot(after)
        assertEquals(0, TapNibbles.delta(after.taps[b].toInt(), merged.taps[b].toInt()))
    }
}

/** A volume key mapped to a button never releases a hold it did not make. */
class KeyHoldTest {
    private fun held(state: PadState, bit: Int) = (state.heldButtons ushr bit) and 1 != 0

    @Test
    fun anIgnoredKeyDownDoesNotLetItsKeyUpReleaseAFinger() {
        val state = PadState()
        val holds = ButtonHolds(state)
        val key = KeyHold(holds)
        holds.press(PadButton.CROSS) // a finger on Cross; the key's down came while the menu was open
        key.up()
        assertTrue("the finger still holds Cross", held(state, PadButton.CROSS))
    }

    @Test
    fun aHoldWipedWithEveryFingerIsNotReleasedAgainByTheKeyUp() {
        val state = PadState()
        val holds = ButtonHolds(state)
        val key = KeyHold(holds)
        assertTrue(key.down(PadButton.R1))
        assertTrue(key.isHolding)
        // The screen paused (every finger and key hold released), then a finger pressed R1.
        holds.clear()
        state.releaseAll()
        assertFalse(key.isHolding)
        holds.press(PadButton.R1)
        key.up()
        assertTrue("the finger's R1 survives the stale key-up", held(state, PadButton.R1))
    }

    @Test
    fun ordinaryPressAndReleaseWithRepeatsAndASharedButton() {
        val state = PadState()
        val holds = ButtonHolds(state)
        val key = KeyHold(holds)
        assertTrue(key.down(PadButton.TRIANGLE))
        assertEquals(1, state.tapCounter(PadButton.TRIANGLE))
        assertFalse("a second down without an up is not a press", key.down(PadButton.TRIANGLE))
        assertEquals(1, state.tapCounter(PadButton.TRIANGLE))
        holds.press(PadButton.TRIANGLE) // a finger joins
        key.up()
        assertTrue("released only when the finger lets go too", held(state, PadButton.TRIANGLE))
        holds.release(PadButton.TRIANGLE)
        assertFalse(held(state, PadButton.TRIANGLE))
        assertFalse("an unmapped or invalid bit does nothing", key.down(-1))
        assertFalse(key.down(Slp.PAD_BUTTONS))
        key.up()
        assertEquals(0, state.heldButtons)
    }
}

/** PadState ignores stick, trigger and touch indexes other than 0 and 1. */
class PadStateBoundsTest {
    @Test
    fun outOfRangeIndexesNeverReachAnotherField() {
        val state = PadState()
        state.setStick(0, 111, -222)
        state.setStick(1, 333, 444)
        state.setStick(2, 9, 9) // would shift by 64, i.e. by 0: the left stick
        state.setStick(-1, 7, 7) // would shift by -32, i.e. by 32: the right stick
        assertEquals(111, state.stickX(0))
        assertEquals(-222, state.stickY(0))
        assertEquals(333, state.stickX(1))
        assertEquals(444, state.stickY(1))
        state.setTrigger(0, 1000)
        state.setTrigger(2, 5000) // shift 32 on an Int is shift 0: L2
        state.setTrigger(-1, 5000) // shift -16 is shift 16: R2
        assertEquals(1000, state.trigger(0))
        assertEquals(0, state.trigger(1))
        val before = PadFrame()
        state.snapshot(before)
        state.setTouch(2, true, 5, 100, 100)
        state.setTouch(-1, true, 5, 100, 100)
        val after = PadFrame()
        state.snapshot(after)
        assertEquals(before.touch0Id, after.touch0Id)
        assertEquals(before.touch1Id, after.touch1Id)
        assertEquals(before.touch0X, after.touch0X)
        assertEquals(before.touch1X, after.touch1X)
    }
}

/** Stored layouts: bindings validated, and layouts fitted to a new screen keep what plays there. */
class LayoutFitAndBindingTest {
    @Test
    fun controlsWithABindingThatCannotBeRightAreDropped() {
        val text = """{"v":1,"id":"custom-1","name":"x","style":"xbox","controls":[
            {"id":"s2","kind":"stick","binding":2,"cx":0.3,"cy":0.7,"width_dp":116,"height_dp":116},
            {"id":"tneg","kind":"trigger","binding":-1,"cx":0.1,"cy":0.1,"width_dp":171,"height_dp":47},
            {"id":"b18","kind":"button","binding":18,"cx":0.5,"cy":0.5,"width_dp":40,"height_dp":40},
            {"id":"bneg","kind":"button","binding":-3,"cx":0.5,"cy":0.5,"width_dp":40,"height_dp":40},
            {"id":"rs","kind":"stick","binding":1,"cx":0.7,"cy":0.7,"width_dp":116,"height_dp":116},
            {"id":"share","kind":"button","binding":17,"cx":0.5,"cy":0.6,"width_dp":61,"height_dp":25},
            {"id":"dpad","kind":"dpad","binding":7,"cx":0.3,"cy":0.8,"width_dp":127,"height_dp":127}]}"""
        val l = LayoutCodec.decode(text)
        assertNotNull(l)
        l!!
        assertEquals(listOf("rs", "share", "dpad"), l.controls.map { it.id })
        assertEquals(1, l.control("rs")!!.binding)
        assertEquals(PadButton.SHARE, l.control("share")!!.binding)
        assertEquals("fixed-bit kinds store 0", 0, l.control("dpad")!!.binding)
    }

    @Test
    fun fittingToASmallerScreenBakesTheShrinkSoSavingKeepsWhatPlays() {
        val w = 640f
        val h = 320f
        for (def in listOf(DefaultLayouts.playStation(), DefaultLayouts.xbox())) {
            val s = PadGeometry.fitScale(def, w, h)
            assertTrue("${def.id} shrinks on $w x $h: $s", s < 1f)
            // The old editor saved the full-size controls with this screen as the reference:
            // the play screen then no longer shrank them.
            val naive = def.copy(refWidthDp = w, refHeightDp = h)
            assertEquals(1f, PadGeometry.fitScale(naive, w, h), 0f)
            val fitted = PadGeometry.fitToArea(def, w, h)
            assertEquals(w, fitted.refWidthDp, 0f)
            assertEquals(h, fitted.refHeightDp, 0f)
            assertEquals("already fitted: no further shrink", 1f, PadGeometry.fitScale(fitted, w, h), 1e-4f)
            val a = Box()
            val b = Box()
            for (i in def.controls.indices) {
                val c = def.controls[i]
                val f = fitted.controls[i]
                PadGeometry.rect(c, w, h, s, 1f, a) // what the play screen draws from the original
                PadGeometry.rect(f, w, h, 1f, 1f, b) // what it draws from the fitted copy
                assertEquals(c.id, a.l, b.l, 1e-3f)
                assertEquals(c.id, a.t, b.t, 1e-3f)
                assertEquals(c.id, a.r, b.r, 1e-3f)
                assertEquals(c.id, a.b, b.b, 1e-3f)
                if (c.kind == ControlKind.FACE) assertEquals(c.options.faceButtonDp * s, f.options.faceButtonDp, 1e-3f)
            }
            assertEquals("fitting twice changes nothing", fitted, PadGeometry.fitToArea(fitted, w, h))
            assertEquals("shrunk sizes (below 24 dp) survive storage", fitted, LayoutCodec.decode(LayoutCodec.encode(fitted)))
        }
    }

    @Test
    fun onTheReferenceScreenFittingOnlyRestatesTheReference() {
        val ps = DefaultLayouts.playStation()
        assertEquals(ps, PadGeometry.fitToArea(ps, 882f, 411f))
        // A larger screen never grows anything.
        val big = PadGeometry.fitToArea(ps, 1000f, 480f)
        assertEquals(ps.controls, big.controls)
        assertEquals(1000f, big.refWidthDp, 0f)
    }
}

/** The sender while a controller link is paused, against a recording path (no sockets). */
class PadPausedLinkTest {
    private val key = PairingCode.deriveKey("SLIPSTREAMTEST22")

    private class Recorder : Transport {
        override val slot: Int = LinkEngine.SLOT_WIFI
        override val label: String = "recorder"
        val ns = LongArray(20_000)
        val flags = IntArray(20_000)
        @Volatile var count = 0
        override fun start() = Unit
        override fun stop() = Unit
        override fun canSend(): Boolean = true
        override fun send(packet: ByteArray, len: Int) {
            val i = count
            if (i >= ns.size) return
            ns[i] = System.nanoTime()
            flags[i] = packet[if (len == Slp.PAD_LEN) 41 else 40].toInt() and 0xFF
            count = i + 1
        }
    }

    private fun config() = LinkConfig(key = key, host = null, useWifi = false, useUsb = false, rateHz = 500)

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(1)
        }
        return cond()
    }

    @Test
    fun aPausedPadLinkSlowsItsIdleRepeatAndSendsTheUnpauseAtOnce() {
        val pad = PadState() // starts PAUSED, like the play screen before onResume
        val rec = Recorder()
        val engine = LinkEngine(ControllerState(), pad, config(), null, listOf(rec), PacketSource.PAD)
        engine.start()
        try {
            assertTrue("the first packet goes out at once", waitFor(200) { rec.count >= 1 })
            Thread.sleep(500)
            val paused = rec.count
            assertTrue("about 50 Hz while PAUSED, got $paused in 0.5 s", paused in 10..45)
            for (i in 0 until paused) assertTrue(rec.flags[i] and Slp.FLAG_PAUSED != 0)
            val t0 = System.nanoTime()
            pad.setFlag(Slp.FLAG_PAUSED, false)
            assertTrue(waitFor(200) { rec.count > paused })
            var first = -1
            for (i in paused until rec.count) if (rec.flags[i] and Slp.FLAG_PAUSED == 0) {
                first = i
                break
            }
            assertTrue(first >= 0)
            val delayMs = (rec.ns[first] - t0) / 1e6
            assertTrue("unpause sent at once, took $delayMs ms", delayMs < 8.0)
            val start = rec.count
            Thread.sleep(300)
            val playing = rec.count - start
            assertTrue("500 Hz again when playing, got $playing in 0.3 s", playing >= 100)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun theWheelLinkKeepsItsRateWhilePaused() {
        val state = ControllerState() // starts PAUSED
        val rec = Recorder()
        val engine = LinkEngine(state, config(), null, listOf(rec))
        engine.start()
        try {
            Thread.sleep(300)
        } finally {
            engine.stop()
        }
        assertTrue(state.flagBits and Slp.FLAG_PAUSED != 0)
        assertTrue("500 Hz as in 0.1.0, got ${rec.count} in 0.3 s", rec.count >= 100)
    }
}

/**
 * The controller input path (touch math, state writes, snapshot) allocates nothing. The unit
 * test classpath is the Android stub jar, so the JVM's per-thread allocation counter is
 * reached by reflection; its own small, constant cost is measured and taken off.
 */
class PadHotPathAllocationTest {
    private val bean: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")

    private fun allocatedBytes(): Long = allocated.invoke(bean) as Long

    @Test
    fun touchSensorAndSnapshotPathsAllocateNothing() {
        repeat(100) { allocatedBytes() } // the reflective call settles
        val c0 = allocatedBytes()
        val c1 = allocatedBytes()
        val probe = c1 - c0
        val state = PadState()
        val holds = ButtonHolds(state)
        val frame = PadFrame()
        var sink = 0L
        fun work(i: Int) {
            val bit = i % Slp.PAD_BUTTONS
            holds.press(bit)
            val dx = ((i * 37) % 200 - 100).toFloat()
            val dy = ((i * 53) % 200 - 100).toFloat()
            val v = StickMath.output(dx, dy, 58f, StickMath.DEFAULT_DEADZONE, 1.4f)
            state.setStick(i and 1, StickMath.x(v), StickMath.y(v))
            state.setTrigger(i and 1, TriggerMath.output(TriggerMath.value(0f, (i % 120).toFloat(), 100f), 1f))
            state.setTouch(i and 1, i and 2 != 0, i and 0x7F, (i * 7) and 0xFFFF, (i * 11) and 0xFFFF)
            state.setGyroAim(GyroAim.deflection(dx / 10f, dy / 10f, 1f, false))
            state.setGyro(MotionMath.gyroUnits(dx / 50f), MotionMath.gyroUnits(dy / 50f), 0)
            state.setAccel(MotionMath.accelUnits(dx / 10f), MotionMath.accelUnits(9.8f), 0)
            state.setFlag(Slp.FLAG_MOTION, true)
            if (PadTouchRules.pauseRingTakes(dx, 22f, 12f, i and 4 != 0)) sink++
            state.snapshot(frame)
            holds.release(bit)
            sink += frame.rx + frame.taps[bit]
        }
        repeat(50_000) { work(it) } // warm up
        val before = allocatedBytes()
        repeat(100_000) { work(it) }
        val used = allocatedBytes() - before - probe
        // One allocation per round, however small, would be at least 1.6 MB here.
        assertTrue("allocated $used bytes over 100000 rounds (sink $sink)", used < 8192)
    }
}
