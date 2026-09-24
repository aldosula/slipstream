package com.slipstream.wheel.protocol

import java.net.URLDecoder

/** What the hub's QR code carries (PROTOCOL.md section 2). */
data class PairInfo(
    val code: String,
    val name: String,
    val udpPort: Int,
    val tcpPort: Int,
    val hosts: List<String>,
)

/**
 * Parser for `slipstream://pair?v=1&code=...&name=...&port=47800&tcp=47802&host=...&host=...`.
 * Returns null when the scheme, version or code is wrong. Unknown keys are ignored, invalid
 * host entries are skipped, missing ports take the protocol defaults.
 */
object PairUri {
    const val PREFIX = "slipstream://pair"
    const val DEFAULT_NAME = "Slipstream Hub"

    fun parse(text: String): PairInfo? {
        val s = text.trim()
        if (!s.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        var rest = s.substring(PREFIX.length)
        if (rest.startsWith("/")) rest = rest.substring(1)
        if (!rest.startsWith("?")) return null
        val query = rest.substring(1).substringBefore('#')

        var version: String? = null
        var code: String? = null
        var name: String? = null
        var udp = Slp.PORT_UDP
        var tcp = Slp.PORT_TCP
        val hosts = ArrayList<String>()

        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val k = decode(if (eq < 0) part else part.substring(0, eq)) ?: return null
            val v = decode(if (eq < 0) "" else part.substring(eq + 1)) ?: return null
            when (k) {
                "v" -> version = v
                "code" -> code = v
                "name" -> name = v
                "port" -> udp = port(v) ?: return null
                "tcp" -> tcp = port(v) ?: return null
                "host" -> if (isIpv4(v) && v !in hosts) hosts.add(v)
            }
        }
        if (version != "1") return null
        val normalized = PairingCode.normalize(code ?: return null) ?: return null
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME
        return PairInfo(normalized, cleanName, udp, tcp, hosts)
    }

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false
            if (p.toInt() > 255) return false
        }
        return true
    }

    private fun port(v: String): Int? = v.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
