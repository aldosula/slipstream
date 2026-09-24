package com.slipstream.wheel

import org.json.JSONObject

/** Loads docs/test-vectors.json, which the build puts on the unit test classpath. */
object TestVectors {
    val json: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("test-vectors.json")
            ?: error("test-vectors.json is not on the test classpath (android.sourceSets test resources)")
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    fun hex(b: ByteArray, len: Int = b.size): String {
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) sb.append(String.format("%02x", b[i].toInt() and 0xFF))
        return sb.toString()
    }
}
