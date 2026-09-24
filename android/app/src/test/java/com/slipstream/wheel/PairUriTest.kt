package com.slipstream.wheel

import com.slipstream.wheel.protocol.PairUri
import com.slipstream.wheel.protocol.Slp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairUriTest {
    @Test
    fun specExample() {
        val info = PairUri.parse(
            "slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&name=RACING-PC&port=47800&tcp=47802&host=192.168.1.20&host=10.0.0.4",
        )
        assertNotNull(info)
        assertEquals("ABCDEFGHIJKLMNOP", info!!.code)
        assertEquals("RACING-PC", info.name)
        assertEquals(47800, info.udpPort)
        assertEquals(47802, info.tcpPort)
        assertEquals(listOf("192.168.1.20", "10.0.0.4"), info.hosts)
    }

    @Test
    fun codeIsNormalized() {
        val info = PairUri.parse("slipstream://pair?v=1&code=slip-stre-amte-st22")
        assertEquals("SLIPSTREAMTEST22", info!!.code)
    }

    @Test
    fun defaultsWhenOptionalKeysAreMissing() {
        val info = PairUri.parse("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP")!!
        assertEquals(Slp.PORT_UDP, info.udpPort)
        assertEquals(Slp.PORT_TCP, info.tcpPort)
        assertEquals(PairUri.DEFAULT_NAME, info.name)
        assertTrue(info.hosts.isEmpty())
    }

    @Test
    fun percentEncodingAndOddFormsAreAccepted() {
        val info = PairUri.parse("  SLIPSTREAM://pair/?v=1&name=Aldo%27s%20PC&code=ABCD%2DEFGH%2DIJKL%2DMNOP&host=10.0.0.4&host=10.0.0.4&extra=1#x ")
        assertNotNull(info)
        assertEquals("Aldo's PC", info!!.name)
        assertEquals("ABCDEFGHIJKLMNOP", info.code)
        assertEquals("duplicates dropped", listOf("10.0.0.4"), info.hosts)
    }

    @Test
    fun invalidHostsAreSkipped() {
        val info = PairUri.parse("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&host=999.1.1.1&host=pc.local&host=1.2.3&host=172.16.0.9")!!
        assertEquals(listOf("172.16.0.9"), info.hosts)
    }

    @Test
    fun customPorts() {
        val info = PairUri.parse("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&port=50000&tcp=50002")!!
        assertEquals(50000, info.udpPort)
        assertEquals(50002, info.tcpPort)
    }

    @Test
    fun rejectsWrongSchemeVersionCodeOrPort() {
        assertNull(PairUri.parse("https://pair?v=1&code=ABCDEFGHIJKLMNOP"))
        assertNull(PairUri.parse("slipstream://other?v=1&code=ABCDEFGHIJKLMNOP"))
        assertNull(PairUri.parse("slipstream://pairing?v=1&code=ABCDEFGHIJKLMNOP"))
        assertNull(PairUri.parse("slipstream://pair?v=2&code=ABCDEFGHIJKLMNOP"))
        assertNull(PairUri.parse("slipstream://pair?code=ABCDEFGHIJKLMNOP"))
        assertNull(PairUri.parse("slipstream://pair?v=1"))
        assertNull(PairUri.parse("slipstream://pair?v=1&code=ABCD-EFGH-IJKL-MN9P"))
        assertNull(PairUri.parse("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&port=0"))
        assertNull(PairUri.parse("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&tcp=70000"))
        assertNull(PairUri.parse("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&name=%ZZ"))
        assertNull(PairUri.parse(""))
    }
}
