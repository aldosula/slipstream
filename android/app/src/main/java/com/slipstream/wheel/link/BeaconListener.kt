package com.slipstream.wheel.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.slipstream.wheel.protocol.Beacon
import com.slipstream.wheel.protocol.PairingKey
import com.slipstream.wheel.protocol.Slp
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/** A hub seen on the network. [address] is the beacon's source IP. */
data class DiscoveredHub(
    val name: String,
    val address: String,
    val udpPort: Int,
    val tcpPort: Int,
    val fingerprintHex: String,
    val paired: Boolean,
    val lastSeenMs: Long,
)

/**
 * Listens for hub beacons on UDP 47801 while the connect screen (or, for the Wi-Fi path, the
 * drive screen) is open. Holds a WifiManager.MulticastLock meanwhile, because many phones
 * filter broadcast frames in power save. Every hub is listed; the one whose fingerprint
 * matches our key is flagged paired, which is what auto-connect uses. Callbacks arrive on
 * the main thread.
 *
 * Beacons are unauthenticated, so a [BeaconGate] on the listener thread bounds what a flood
 * can cost: at most one publish per source per second, at most 16 sources.
 */
class BeaconListener(context: Context, private val listener: (List<DiscoveredHub>) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val hubs = LinkedHashMap<String, DiscoveredHub>()
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var pairing: PairingKey? = null
    private var thread: Thread? = null

    private val expire = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (hubs.values.removeAll { now - it.lastSeenMs > EXPIRE_MS }) publish()
            if (running) main.postDelayed(this, 1000)
        }
    }

    /** The key the phone holds, or null. Changing it re-flags the list. */
    fun setPairing(key: PairingKey?) {
        pairing = key
        main.post {
            for ((k, h) in hubs.entries.toList()) {
                val fp = hexToBytes(h.fingerprintHex)
                hubs[k] = h.copy(paired = key?.matches(fp) == true)
            }
            publish()
        }
    }

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        val wifi = app.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("slipstream-discovery")?.also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        thread = Thread({ loop(gen) }, "slip-beacon").also {
            it.isDaemon = true
            it.start()
        }
        main.postDelayed(expire, 1000)
    }

    fun stop() {
        running = false
        // A thread of this run that is still between sockets sees the new generation and
        // exits, so a quick stop and start (a rotation) never leaves two listeners behind.
        generation++
        main.removeCallbacks(expire)
        socket?.close()
        thread?.interrupt()
        thread = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun alive(gen: Int): Boolean = running && generation == gen

    private fun loop(gen: Int) {
        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)
        val gate = BeaconGate()
        while (alive(gen)) {
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(Slp.PORT_BEACON))
                }
            } catch (e: SocketException) {
                // Port busy, or no network yet: try again.
                sleepQuietly(1000)
                continue
            }
            socket = s
            if (!alive(gen)) s.close() // stop() ran before the socket was published
            try {
                while (alive(gen)) {
                    pkt.setLength(buf.size)
                    try {
                        s.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val src = pkt.address as? Inet4Address ?: continue
                    val len = pkt.length
                    // Inet4Address.hashCode() is the address itself: no allocation per packet.
                    if (!gate.admit(src.hashCode(), BeaconGate.hash(buf, 0, len), SystemClock.elapsedRealtime())) continue
                    val beacon = Beacon.decode(buf, 0, len) ?: continue
                    onBeacon(beacon, src.hostAddress ?: continue, gen)
                }
            } catch (e: IOException) {
                // socket closed by stop(), or the network changed: rebind
            } catch (e: RuntimeException) {
                // never die silently: rebind
            } finally {
                s.close()
                if (socket === s) socket = null
            }
            if (alive(gen)) sleepQuietly(300)
        }
    }

    private fun onBeacon(b: Beacon, address: String, gen: Int) {
        val paired = b.matches(pairing?.fingerprint)
        val hub = DiscoveredHub(
            name = b.name.ifBlank { "Slipstream Hub" },
            address = address,
            udpPort = b.udpPort,
            tcpPort = b.tcpPort,
            fingerprintHex = PairingKey.hex(b.fingerprint),
            paired = paired,
            lastSeenMs = SystemClock.elapsedRealtime(),
        )
        main.post {
            if (!alive(gen)) return@post
            val old = hubs.put(address, hub)
            if (old == null || old.name != hub.name || old.paired != hub.paired ||
                old.udpPort != hub.udpPort || old.fingerprintHex != hub.fingerprintHex
            ) {
                publish()
            }
        }
    }

    private fun publish() {
        val list = hubs.values.sortedWith(compareByDescending<DiscoveredHub> { it.paired }.thenBy { it.name })
        listener(list)
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // stop() interrupts
        }
    }

    companion object {
        const val EXPIRE_MS = 5000L

        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
