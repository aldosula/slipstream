package com.slipstream.wheel.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Pins the process to the network that directly reaches the hub, for the drive session, and
 * keeps that pin correct while networks come and go.
 *
 * Why pin: when the Wi-Fi has no internet (a travel router, a LAN-only access point) and
 * mobile data is on, Android makes cellular the default network, and sockets that are not
 * bound to a network send even LAN addresses out of the cellular interface, where they
 * vanish. Binding to the network whose connected route contains the hub fixes that. When no
 * network matches (for example the PC is on the phone's own hotspot) nothing is bound and
 * the system routing applies. Loopback (the USB path) always routes locally.
 *
 * Why watch: a process binding outlives its network. After a Wi-Fi drop the process would
 * stay bound to a network that no longer exists, and every new IPv4 or IPv6 socket fails
 * with ENONET, including the Wi-Fi socket rebuilt after the reconnect and the USB socket to
 * 127.0.0.1. So the binding is re-evaluated on every network event: it moves to the network
 * that now reaches the hub, or is dropped, and [onChange] tells the link to rebuild its
 * Wi-Fi socket on the new route. Callbacks run on the ConnectivityManager thread.
 */
class NetworkBinder(context: Context, private val onChange: () -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var host: Inet4Address? = null
    private var active = false
    private var bound: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Starts managing the binding for [host] (null: nothing to bind yet). Main thread. */
    fun start(host: InetAddress?) {
        val c = cm ?: return
        synchronized(lock) {
            this.host = host as? Inet4Address
            active = true
        }
        if (callback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = reevaluate(null)
                override fun onLost(network: Network) = reevaluate(network)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = reevaluate(null)
            }
            // Every network, with or without internet: the hub is on a LAN.
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                c.registerNetworkCallback(request, cb)
                callback = cb
            } catch (e: RuntimeException) {
                // Too many callbacks or a restricted build: the one-shot evaluation below still runs.
            }
        }
        reevaluate(null)
    }

    /** The hub moved (a beacon from another address). */
    fun setHost(host: InetAddress?) {
        synchronized(lock) { this.host = host as? Inet4Address }
        reevaluate(null)
    }

    /** Stops watching and drops the binding. Main thread. */
    fun stop() {
        val c = cm ?: return
        callback?.let {
            try {
                c.unregisterNetworkCallback(it)
            } catch (e: RuntimeException) {
                // Not registered: nothing to undo.
            }
        }
        callback = null
        synchronized(lock) {
            active = false
            if (bound != null) {
                bindQuietly(c, null)
                bound = null
            }
        }
    }

    private fun reevaluate(lost: Network?) {
        val c = cm ?: return
        val changed: Boolean
        synchronized(lock) {
            if (!active) return
            val h = host
            val target = if (h == null) null else findNetworkFor(c, h, lost)
            if (target == bound) return
            val ok = target != null && bindQuietly(c, target)
            // A failed bind (the network went away meanwhile) must not leave the old pin.
            if (!ok && bound != null) bindQuietly(c, null)
            val next = if (ok) target else null
            changed = next != bound
            bound = next
        }
        if (changed) onChange()
    }

    private fun bindQuietly(c: ConnectivityManager, network: Network?): Boolean = try {
        c.bindProcessToNetwork(network)
    } catch (e: RuntimeException) {
        false
    }

    private fun findNetworkFor(c: ConnectivityManager, host: Inet4Address, lost: Network?): Network? {
        @Suppress("DEPRECATION")
        val networks = try {
            c.allNetworks
        } catch (e: RuntimeException) {
            return null
        }
        for (n in networks) {
            if (n == lost) continue
            val lp = c.getLinkProperties(n) ?: continue
            // A directly connected (non-default) route that contains the hub.
            if (lp.routes.any { !it.isDefaultRoute && it.matches(host) }) return n
        }
        return null
    }
}
