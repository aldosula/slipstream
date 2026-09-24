package com.slipstream.wheel.link

/**
 * Admission control for beacons, which are unauthenticated: anyone on the LAN can send
 * them, from any source address. Decides on the listener thread, before a beacon is decoded
 * or anything is posted to the main thread, whether it is worth publishing:
 * - a source already known with the same content is republished at most once per
 *   [refreshMs] (enough to keep it from expiring on the screen),
 * - a change (new name, port or fingerprint) is published at once,
 * - at most [maxSources] distinct sources are tracked; a new one beyond that is dropped
 *   until an old one has been quiet for [expireMs].
 * Pure Kotlin, owned by one thread, allocation free.
 */
class BeaconGate(
    private val maxSources: Int = 16,
    private val refreshMs: Long = 900,
    private val expireMs: Long = 5000,
) {
    private val used = BooleanArray(maxSources)
    private val source = IntArray(maxSources)
    private val content = LongArray(maxSources)
    private val lastMs = LongArray(maxSources)

    /**
     * True when the beacon from IPv4 address [src] (as an Int) whose bytes hash to
     * [contentHash] should be published at [nowMs].
     */
    fun admit(src: Int, contentHash: Long, nowMs: Long): Boolean {
        var free = -1
        for (i in 0 until maxSources) {
            if (used[i] && nowMs - lastMs[i] > expireMs) used[i] = false
            if (!used[i]) {
                if (free < 0) free = i
                continue
            }
            if (source[i] != src) continue
            if (content[i] != contentHash || nowMs - lastMs[i] >= refreshMs) {
                content[i] = contentHash
                lastMs[i] = nowMs
                return true
            }
            return false
        }
        if (free < 0) return false
        used[free] = true
        source[free] = src
        content[free] = contentHash
        lastMs[free] = nowMs
        return true
    }

    fun clear() {
        used.fill(false)
    }

    companion object {
        /** FNV-1a over buf[off, off + len). */
        fun hash(buf: ByteArray, off: Int, len: Int): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (i in off until off + len) {
                h = h xor (buf[i].toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
