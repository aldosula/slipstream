package com.slipstream.wheel.ui

import android.content.Context
import android.content.Intent
import com.slipstream.wheel.LinkMode
import com.slipstream.wheel.Settings
import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.link.BeaconListener
import com.slipstream.wheel.link.DiscoveredHub
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.NetworkBinder
import com.slipstream.wheel.link.PacketSource
import com.slipstream.wheel.link.StatusSink
import com.slipstream.wheel.link.WifiLatencyLock
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.protocol.PairUri
import com.slipstream.wheel.protocol.Slp
import java.net.InetAddress

/**
 * The hub link of a controller session: the LinkEngine sending PAD packets on the same
 * transports as the wheel (Wi-Fi, USB or both), the Wi-Fi low-latency lock, network
 * binding, and following the paired hub's beacons while playing. The drive screen keeps its
 * own copy of this logic unchanged.
 */
class HubLink private constructor(
    context: Context,
    private val settings: Settings,
    val engine: LinkEngine,
    useWifi: Boolean,
    key: com.slipstream.wheel.protocol.PairingKey,
) {
    private val wifiLock: WifiLatencyLock? = if (useWifi) WifiLatencyLock(context) else null
    private val networkBinder: NetworkBinder? = if (useWifi) NetworkBinder(context) { engine.onNetworkChanged() } else null
    private var hubs: List<DiscoveredHub> = emptyList()
    private val beacons: BeaconListener? = if (useWifi) {
        BeaconListener(context) { list ->
            hubs = list
            maybeRetargetWifi()
        }.also { it.setPairing(key) }
    } else {
        null
    }
    private var lastRetargetNs = 0L

    fun start() {
        // Before the link opens its sockets: route to the hub over the network that reaches it.
        networkBinder?.start(engine.wifiTarget?.address)
        lastRetargetNs = 0L
        engine.start()
        wifiLock?.acquire()
        beacons?.start()
    }

    fun stop() {
        engine.stop() // sends PAUSED first, over the current routing
        beacons?.stop()
        networkBinder?.stop()
        wifiLock?.release()
    }

    /** Called about ten times a second from the play screen. */
    fun tick() = maybeRetargetWifi()

    /**
     * Moves the Wi-Fi path to the paired hub's beacon address when the current target has
     * been silent for [RETARGET_AFTER_NS]. A working target is never switched.
     */
    private fun maybeRetargetWifi() {
        val e = engine
        if (!e.isRunning || !e.hasWifi || hubs.isEmpty()) return
        val now = System.nanoTime()
        val current = e.wifiTarget
        if (current != null && e.wifiSilentNs(now) < RETARGET_AFTER_NS) return
        if (lastRetargetNs != 0L && now - lastRetargetNs < RETARGET_AFTER_NS) return
        val currentHost = current?.address?.hostAddress
        for (hub in hubs) {
            if (!hub.paired || !PairUri.isIpv4(hub.address)) continue
            if (current != null && hub.address == currentHost && hub.udpPort == current.port) continue
            val address = InetAddress.getByName(hub.address) // numeric literal: no DNS
            e.setWifiTarget(address, hub.udpPort)
            networkBinder?.setHost(address)
            settings.hubHosts = listOf(hub.address) + settings.hubHosts.filter { it != hub.address }
            lastRetargetNs = now
            return
        }
    }

    companion object {
        const val EXTRA_HOST = DriveActivity.EXTRA_HOST
        const val EXTRA_UDP_PORT = DriveActivity.EXTRA_UDP_PORT
        const val EXTRA_TCP_PORT = DriveActivity.EXTRA_TCP_PORT
        private const val RETARGET_AFTER_NS = 3_000_000_000L

        /** Null when there is no pairing key, or the link mode is Bluetooth (wheel only). */
        fun create(context: Context, settings: Settings, intent: Intent, pad: PadState, sink: StatusSink?): HubLink? {
            val mode = settings.mode
            if (mode == LinkMode.BLUETOOTH) return null
            val key = settings.pairingKey() ?: return null
            // Only numeric IPv4 literals are accepted, so this never touches DNS.
            val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { PairUri.isIpv4(it) }?.let { InetAddress.getByName(it) }
            val useWifi = mode == LinkMode.WIFI || mode == LinkMode.MULTIPATH
            val useUsb = mode == LinkMode.USB || mode == LinkMode.MULTIPATH
            val engine = LinkEngine(
                ControllerState(),
                pad,
                LinkConfig(
                    key = key.key,
                    host = host,
                    udpPort = intent.getIntExtra(EXTRA_UDP_PORT, Slp.PORT_UDP),
                    tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, Slp.PORT_TCP),
                    useWifi = useWifi,
                    useUsb = useUsb,
                    rateHz = settings.rateHz,
                ),
                sink,
                PacketSource.PAD,
            )
            return HubLink(context, settings, engine, useWifi, key)
        }
    }
}
