package com.slipstream.wheel

import com.slipstream.wheel.link.HostPicker
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.link.RttMath
import com.slipstream.wheel.link.RttTracker
import com.slipstream.wheel.protocol.StatusPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RttTest {
    @Test
    fun rttIsEchoMinusHoldWithU32Wrap() {
        assertEquals(500, RttMath.rttUs(nowUs = 1000, echoTUs = 400, holdUs = 100))
        // t_us wrapped between the send and the STATUS.
        assertEquals(306, RttMath.rttUs(nowUs = 100, echoTUs = 0xFFFFFF00.toInt(), holdUs = 50))
        assertEquals("negative is not a round trip", -1, RttMath.rttUs(nowUs = 100, echoTUs = 200, holdUs = 0))
        assertEquals("absurd is not a round trip", -1, RttMath.rttUs(nowUs = 10_000_000, echoTUs = 0, holdUs = 0))
    }

    @Test
    fun rttFieldIn100usSaturating() {
        assertEquals(0, RttMath.to100us(Double.NaN))
        assertEquals("measured but tiny is not 'unknown'", 1, RttMath.to100us(20.0))
        assertEquals(23, RttMath.to100us(2345.0))
        assertEquals(65535, RttMath.to100us(10_000_000.0))
    }

    @Test
    fun ewmaIsOneEighth() {
        val t = RttTracker()
        t.add(1000, 0)
        assertEquals(1000.0, t.smoothedUs, 0.0)
        t.add(2000, 1)
        assertEquals(1125.0, t.smoothedUs, 1e-9)
        t.add(2000, 2)
        assertEquals(1125.0 + (2000 - 1125.0) / 8, t.smoothedUs, 1e-9)
    }

    @Test
    fun minMaxOverLastFiveSeconds() {
        val s = 1_000_000_000L
        val t = RttTracker()
        t.add(100, 0)
        t.add(5000, 1 * s)
        t.add(900, 2 * s)
        assertEquals(100, t.min5s(2 * s))
        assertEquals(5000, t.max5s(2 * s))
        assertEquals("the 100 us sample is older than 5 s", 900, t.min5s(6 * s))
        assertEquals(5000, t.max5s(5 * s))
        assertEquals(900, t.max5s(6 * s))
        assertEquals(-1, t.min5s(20 * s))
    }
}

class LinkStatsTest {
    private val s = 1_000_000_000L

    private var nextSeq = 0

    /** A STATUS as the hub sends it: last_seq moves forward with every report. */
    private fun status(epoch: Int, echo: Int, hold: Int, accepted: Int, missing: Int, lastSeq: Int = ++nextSeq) = StatusPacket().apply {
        this.epoch = epoch
        this.lastSeq = lastSeq
        echoTUs = echo
        holdUs = hold
        this.accepted = accepted
        this.missing = missing
        output = 1
    }

    @Test
    fun otherEpochIsIgnored() {
        val st = LinkStats(2)
        st.reset(42)
        st.transports[0].enabled = true
        st.transports[0].open = true
        assertFalse(st.onStatus(0, status(7, 0, 0, 10, 0), 10 * s))
        assertEquals(0, st.rtt100us)
        val snap = LinkStats.Snapshot(2)
        st.snapshot(snap, 10 * s)
        assertEquals(LinkStats.TransportState.OPEN, snap.state[0])
        assertTrue(snap.hubTracksOtherEpoch)
    }

    @Test
    fun rttLossAndLiveness() {
        val st = LinkStats(2)
        st.reset(42)
        st.transports[0].enabled = true
        st.transports[0].open = true
        val now = 10 * s
        val nowUs = (now / 1000).toInt()
        assertTrue(st.onStatus(0, status(42, nowUs - 3000, 500, 100, 0), now))
        assertEquals(25, st.rtt100us) // 2500 us
        assertTrue(st.onStatus(0, status(42, nowUs + 1_000_000 - 3000, 500, 190, 10), now + s))
        val snap = LinkStats.Snapshot(2)
        st.snapshot(snap, now + s)
        assertEquals(LinkStats.TransportState.LIVE, snap.state[0])
        assertEquals(LinkStats.TransportState.OFF, snap.state[1])
        assertEquals(2500.0, snap.linkRttUs, 1e-9)
        assertEquals(10.0 / 200.0, snap.lossTotal, 1e-12)
        assertEquals("last second: 10 missing of 100", 0.1, snap.lossLastSecond, 1e-12)
        assertEquals(1, snap.output)
        st.snapshot(snap, now + 3 * s)
        assertEquals("no STATUS for 2 s", LinkStats.TransportState.OPEN, snap.state[0])
    }

    @Test
    fun bestLiveTransportGivesTheLinkRtt() {
        val st = LinkStats(2)
        st.reset(1)
        for (t in st.transports) {
            t.enabled = true
            t.open = true
        }
        val now = 50 * s
        val nowUs = (now / 1000).toInt()
        st.onStatus(0, status(1, nowUs - 4000, 0, 1, 0), now)
        st.onStatus(1, status(1, nowUs - 1000, 0, 1, 0), now)
        assertEquals(10, st.rtt100us)
        val snap = LinkStats.Snapshot(2)
        st.snapshot(snap, now)
        assertEquals(2, snap.liveCount)
        assertEquals(1000.0, snap.linkRttUs, 1e-9)
    }
}

class LinkStatsOrderingTest {
    private val s = 1_000_000_000L

    private fun status(lastSeq: Int, accepted: Int, missing: Int, echo: Int) = StatusPacket().apply {
        epoch = 9
        this.lastSeq = lastSeq
        echoTUs = echo
        this.accepted = accepted
        this.missing = missing
        output = 1
    }

    @Test
    fun aLateCopyOnTheSlowPathDoesNotMoveCountersBack() {
        val st = LinkStats(2)
        st.reset(9)
        for (t in st.transports) {
            t.enabled = true
            t.open = true
        }
        val t0 = 20 * s
        fun us(ns: Long) = (ns / 1000).toInt()
        // USB: report for seq 1000, then one second later seq 1500 (10 missing in between).
        st.onStatus(1, status(1000, 990, 10, us(t0) - 1000), t0)
        st.onStatus(1, status(1500, 1480, 20, us(t0 + s) - 1000), t0 + s)
        // Wi-Fi: the copy of the seq 1000 report arrives late, after the newer one.
        st.onStatus(0, status(1000, 990, 10, us(t0) - 1000), t0 + s + 1)
        val snap = LinkStats.Snapshot(2)
        st.snapshot(snap, t0 + s + 2)
        assertEquals("counters stay at the newest report", 1480L, snap.accepted)
        assertEquals(20L, snap.missing)
        assertEquals("last second: 10 missing of 500", 10.0 / 500.0, snap.lossLastSecond, 1e-12)
        // The next window is measured from the newest report, not from the late copy.
        st.onStatus(1, status(2000, 1980, 20, us(t0 + 2 * s) - 1000), t0 + 2 * s + 2)
        st.snapshot(snap, t0 + 2 * s + 3)
        assertEquals("no loss in the second window", 0.0, snap.lossLastSecond, 1e-12)
    }

    @Test
    fun sameReportOnTwoPathsCountsOnceButMeasuresBoth() {
        val st = LinkStats(2)
        st.reset(9)
        val t0 = 30 * s
        val nowUs = (t0 / 1000).toInt()
        assertTrue(st.onStatus(1, status(7, 7, 0, nowUs - 1000), t0))
        assertTrue(st.onStatus(0, status(7, 7, 0, nowUs - 5000), t0))
        assertEquals(1000.0, st.transports[1].rtt.smoothedUs, 1e-9)
        assertEquals(5000.0, st.transports[0].rtt.smoothedUs, 1e-9)
        assertEquals("link RTT is the faster path", 10, st.rtt100us)
    }
}

class HostPickerTest {
    @Test
    fun prefersSameSubnet() {
        assertEquals("192.168.1.20", HostPicker.best(listOf("10.0.0.4", "192.168.1.20"), listOf("192.168.1.55")))
        assertEquals("10.0.0.4", HostPicker.best(listOf("10.0.0.4", "192.168.1.20"), listOf("172.20.1.3")))
        assertNull(HostPicker.best(emptyList(), listOf("192.168.1.55")))
        assertEquals(
            listOf("192.168.43.7", "10.0.0.4"),
            HostPicker.rank(listOf("10.0.0.4", "192.168.43.7"), listOf("192.168.43.1")),
        )
    }
}
