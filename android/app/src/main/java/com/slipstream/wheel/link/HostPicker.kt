package com.slipstream.wheel.link

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Chooses which of the hub's QR addresses to use when no beacon has been seen: the first one
 * on the same /24 as any of the phone's interfaces (Wi-Fi, hotspot, USB tethering), else the
 * first one listed. A beacon's source address always wins over this guess.
 */
object HostPicker {
    fun best(hubHosts: List<String>, phoneAddresses: List<String>): String? {
        if (hubHosts.isEmpty()) return null
        val prefixes = phoneAddresses.mapNotNull { prefix24(it) }.toSet()
        return hubHosts.firstOrNull { prefix24(it) in prefixes } ?: hubHosts.first()
    }

    /** Orders hosts best first, for storing. */
    fun rank(hubHosts: List<String>, phoneAddresses: List<String>): List<String> {
        val first = best(hubHosts, phoneAddresses) ?: return hubHosts
        return listOf(first) + hubHosts.filter { it != first }
    }

    fun prefix24(ip: String): String? {
        val parts = ip.split('.')
        return if (parts.size == 4) parts.subList(0, 3).joinToString(".") else null
    }

    /** IPv4 addresses of every interface that is up and not loopback. */
    fun phoneAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
    } catch (e: Exception) {
        emptyList()
    }
}
