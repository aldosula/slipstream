package com.slipstream.wheel.link

import com.slipstream.wheel.protocol.StatusPacket

/** Receives every authenticated STATUS, on the transport's receive thread. */
fun interface StatusSink {
    fun onStatus(slot: Int, status: StatusPacket, rxNs: Long)
}

/**
 * One path to the hub. [send] is called only from the sender thread and must never block:
 * a stalled path drops its copy of the packet instead of delaying the other paths.
 */
interface Transport {
    /** Index into [LinkStats.transports]. */
    val slot: Int
    val label: String

    fun start()
    fun stop()

    /** True when [send] would put the packet on the wire now. */
    fun canSend(): Boolean

    fun send(packet: ByteArray, len: Int)
}

/**
 * Thread priority helpers. android.os.Process is reached through try/catch because some
 * devices refuse the highest values, and because JVM unit tests run against stub classes.
 */
object ThreadBoost {
    /** Highest priority an app may take, in the order tried. -19 URGENT_AUDIO, -8, -4. */
    private val NICE_ORDER = intArrayOf(-19, -16, -10, -8, -4)

    /** Raises the calling thread as far as it will go. Returns the niceness obtained, or 0. */
    fun raiseCurrent(): Int {
        Thread.currentThread().priority = Thread.MAX_PRIORITY
        for (nice in NICE_ORDER) {
            try {
                android.os.Process.setThreadPriority(nice)
                return nice
            } catch (t: Throwable) {
                // SecurityException, IllegalArgumentException, or a JVM stub: try the next.
            }
        }
        return 0
    }

    fun setCurrent(nice: Int) {
        try {
            android.os.Process.setThreadPriority(nice)
        } catch (t: Throwable) {
            // Not fatal: the thread keeps the default priority.
        }
    }
}
