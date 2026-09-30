package org.ivanivorontsov.tiho.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DnsWireTest {
    @Test fun buildsQuery() {
        val q = DnsWire.buildQuery("example.com", 1)
        val expected = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
        assertArrayEquals(expected, q)
    }

    @Test fun parsesAnswerWithCompression() {
        val q = DnsWire.buildQuery("example.com", 1)
        val resp = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 2 } +
            // CNAME-less: two A records using name pointer 0xC00C, TTL 60 and 120
            byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 216.toByte(), 34) +
            byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 120, 0, 4, 1, 2, 3, 4)
        val (addrs, ttl) = DnsWire.parseAnswers(resp, 1)
        assertEquals(listOf("93.184.216.34", "1.2.3.4"), addrs.map { it.hostAddress })
        assertEquals(60, ttl)
    }
}
