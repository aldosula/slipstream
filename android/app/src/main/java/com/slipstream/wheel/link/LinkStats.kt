package com.slipstream.wheel.link

import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.Seq
import com.slipstream.wheel.protocol.StatusPacket

/** RTT arithmetic of PROTOCOL.md section 6. */
object RttMath {
    /** Anything above this is a stale or bogus echo, not a round trip. */
    const val MAX_VALID_US = 5_000_000

    /**
     * rtt_us = (now_us - echo_t_us) - hold_us with u32 wrapping subtraction.
     * Returns -1 when the result is not a plausible round trip.
     */
    fun rttUs(nowUs: Int, echoTUs: Int, holdUs: Int): Int {
        val r = (nowUs - echoTUs) - holdUs
        return if (r in 0..MAX_VALID_US) r else -1
    }

    /** Smoothed RTT to the INPUT field: units of 100 us, saturating, 0 only when unknown. */
    fun to100us(rttUs: Double): Int {
        if (rttUs.isNaN() || rttUs < 0) return 0
        return ((rttUs + 50.0) / 100.0).toLong().coerceIn(1L, 65535L).toInt()
    }
}

/**
 * EWMA (alpha = 1/8) of the round trip plus min and max over the last 5 s, kept in five
 * one-second buckets so nothing is allocated per sample.
 */
class RttTracker {
    var smoothedUs = Double.NaN
        private set
    var lastUs = -1
        private set
    var samples = 0L
        private set

    private val bucketSec = LongArray(BUCKETS) { Long.MIN_VALUE }
    private val bucketMin = IntArray(BUCKETS)
    private val bucketMax = IntArray(BUCKETS)

    fun reset() {
        smoothedUs = Double.NaN
        lastUs = -1
        samples = 0
        bucketSec.fill(Long.MIN_VALUE)
    }

    fun add(rttUs: Int, nowNs: Long) {
        lastUs = rttUs
        samples++
        smoothedUs = if (smoothedUs.isNaN()) rttUs.toDouble() else smoothedUs + (rttUs - smoothedUs) / 8.0
        val sec = nowNs / 1_000_000_000L
        val i = Math.floorMod(sec, BUCKETS.toLong()).toInt()
        if (bucketSec[i] != sec) {
            bucketSec[i] = sec
            bucketMin[i] = rttUs
            bucketMax[i] = rttUs
        } else {
            if (rttUs < bucketMin[i]) bucketMin[i] = rttUs
            if (rttUs > bucketMax[i]) bucketMax[i] = rttUs
        }
    }

    /** Minimum over the last 5 s, or -1. */
    fun min5s(nowNs: Long): Int {
        val sec = nowNs / 1_000_000_000L
        var m = Int.MAX_VALUE
        for (i in 0 until BUCKETS) if (recent(i, sec)) m = minOf(m, bucketMin[i])
        return if (m == Int.MAX_VALUE) -1 else m
    }

    /** Maximum over the last 5 s, or -1. */
    fun max5s(nowNs: Long): Int {
        val sec = nowNs / 1_000_000_000L
        var m = -1
        for (i in 0 until BUCKETS) if (recent(i, sec)) m = maxOf(m, bucketMax[i])
        return m
    }

    private fun recent(i: Int, sec: Long): Boolean {
        val b = bucketSec[i]
        if (b == Long.MIN_VALUE) return false
        val age = sec - b
        return age >= 0 && age < BUCKETS
    }

    private companion object {
        const val BUCKETS = 5
    }
}

/**
 * Link telemetry: per transport RTT and liveness, loss over the epoch and over the last
 * second, hub output state. Receiver threads write under one small lock (STATUS arrives at
 * 20 Hz per transport); the sender reads only the volatile [rtt100us].
 */
class LinkStats(val transportCount: Int) {
    enum class TransportState { OFF, CONNECTING, OPEN, LIVE }

    class Transport {
        val rtt = RttTracker()

        @Volatile var enabled = false
        @Volatile var open = false
        @Volatile var lastStatusNs = 0L
        @Volatile var sent = 0L
        @Volatile var sendDrops = 0L
        @Volatile var errors = 0L
    }

    /** A copy for the interface thread. Reused, so reading allocates nothing. */
    class Snapshot(n: Int) {
        val state = arrayOfNulls<TransportState>(n)
        val rttUs = DoubleArray(n)
        var linkRttUs = Double.NaN
        var linkMinUs = -1
        var linkMaxUs = -1
        var lossTotal = Double.NaN
        var lossLastSecond = Double.NaN
        var accepted = 0L
        var missing = 0L
        var output = 0
        var outputError = false
        var hubTracksOtherEpoch = false
        var liveCount = 0
        var openCount = 0
    }

    val transports = Array(transportCount) { Transport() }

    /** Epoch of the running link; STATUS for another epoch does not count. */
    @Volatile var epoch = 0

    /** Latest smoothed link RTT in units of 100 us, 0 when unknown. Fed back into INPUT. */
    @Volatile var rtt100us = 0
        private set

    private val lock = Any()
    private var accepted = 0L
    private var missing = 0L
    private var hasCounters = false
    private var countersSeq = 0
    private var hasRef = false
    private var refAccepted = 0L
    private var refMissing = 0L
    private var refNs = 0L
    private var lossLastSecond = Double.NaN
    private var output = 0
    private var outputError = false
    private var otherEpochNs = 0L
    private var lastStatusAnyNs = 0L

    fun reset(newEpoch: Int) = synchronized(lock) {
        epoch = newEpoch
        rtt100us = 0
        accepted = 0
        missing = 0
        hasCounters = false
        countersSeq = 0
        hasRef = false
        lossLastSecond = Double.NaN
        output = 0
        outputError = false
        otherEpochNs = 0
        lastStatusAnyNs = 0
        for (t in transports) {
            t.rtt.reset()
            t.lastStatusNs = 0
            t.sent = 0
            t.sendDrops = 0
            t.errors = 0
        }
    }

    /**
     * One authenticated STATUS arrived on transport [slot] at [nowNs] (System.nanoTime).
     * Returns true when it was for our epoch.
     */
    fun onStatus(slot: Int, s: StatusPacket, nowNs: Long): Boolean = synchronized(lock) {
        val t = transports[slot]
        if (s.epoch != epoch) {
            otherEpochNs = nowNs
            return false
        }
        t.lastStatusNs = nowNs
        lastStatusAnyNs = nowNs
        val nowUs = (nowNs / 1000L).toInt()
        val r = RttMath.rttUs(nowUs, s.echoTUs, s.holdUs)
        if (r >= 0) t.rtt.add(r, nowNs)

        // In multipath the same STATUS arrives on every path, and a copy delayed on Wi-Fi can
        // land after a newer one on USB. The counters only move forward (by last_seq), or the
        // "last second" loss would be computed from counters that went backward.
        if (hasCounters && !Seq.newer(s.lastSeq, countersSeq)) {
            // Same or older report: it still measured this path's RTT, nothing else is newer.
            rtt100us = RttMath.to100us(bestRttLocked(nowNs))
            return true
        }
        hasCounters = true
        countersSeq = s.lastSeq
        accepted = Le.unsigned(s.accepted)
        missing = Le.unsigned(s.missing)
        if (!hasRef) {
            hasRef = true
            refAccepted = accepted
            refMissing = missing
            refNs = nowNs
        } else if (nowNs - refNs >= 1_000_000_000L) {
            val dA = (accepted - refAccepted) and 0xFFFF_FFFFL
            val dM = (missing - refMissing) and 0xFFFF_FFFFL
            lossLastSecond = if (dA + dM > 0) dM.toDouble() / (dA + dM).toDouble() else lossLastSecond
            refAccepted = accepted
            refMissing = missing
            refNs = nowNs
        }
        output = s.outputKind
        outputError = s.outputError
        rtt100us = RttMath.to100us(bestRttLocked(nowNs))
        true
    }

    /** Smallest smoothed RTT among live transports: the hub applies whichever copy is first. */
    private fun bestRttLocked(nowNs: Long): Double {
        var best = Double.NaN
        for (t in transports) {
            if (nowNs - t.lastStatusNs > LIVE_NS || t.lastStatusNs == 0L) continue
            val v = t.rtt.smoothedUs
            if (!v.isNaN() && (best.isNaN() || v < best)) best = v
        }
        return best
    }

    fun stateOf(slot: Int, nowNs: Long): TransportState {
        val t = transports[slot]
        return when {
            !t.enabled -> TransportState.OFF
            !t.open -> TransportState.CONNECTING
            t.lastStatusNs != 0L && nowNs - t.lastStatusNs <= LIVE_NS -> TransportState.LIVE
            else -> TransportState.OPEN
        }
    }

    fun snapshot(out: Snapshot, nowNs: Long) = synchronized(lock) {
        var live = 0
        var open = 0
        var minUs = -1
        var maxUs = -1
        var bestSlot = -1
        var best = Double.NaN
        for (i in 0 until transportCount) {
            val st = stateOf(i, nowNs)
            out.state[i] = st
            val t = transports[i]
            out.rttUs[i] = if (st == TransportState.LIVE) t.rtt.smoothedUs else Double.NaN
            if (st == TransportState.LIVE) live++
            if (st == TransportState.LIVE || st == TransportState.OPEN) open++
            val v = out.rttUs[i]
            if (!v.isNaN() && (best.isNaN() || v < best)) {
                best = v
                bestSlot = i
            }
        }
        if (bestSlot >= 0) {
            minUs = transports[bestSlot].rtt.min5s(nowNs)
            maxUs = transports[bestSlot].rtt.max5s(nowNs)
        }
        out.liveCount = live
        out.openCount = open
        out.linkRttUs = best
        out.linkMinUs = minUs
        out.linkMaxUs = maxUs
        out.accepted = accepted
        out.missing = missing
        out.lossTotal = if (accepted + missing > 0) missing.toDouble() / (accepted + missing).toDouble() else Double.NaN
        out.lossLastSecond = if (live > 0) lossLastSecond else Double.NaN
        out.output = output
        out.outputError = outputError
        out.hubTracksOtherEpoch = otherEpochNs != 0L && nowNs - otherEpochNs <= LIVE_NS &&
            (lastStatusAnyNs == 0L || nowNs - lastStatusAnyNs > LIVE_NS)
    }

    companion object {
        /** A transport is live when a STATUS for our epoch arrived in the last second. */
        const val LIVE_NS = 1_000_000_000L
    }
}
