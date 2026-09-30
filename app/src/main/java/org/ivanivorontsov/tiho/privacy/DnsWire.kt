package org.ivanivorontsov.tiho.privacy

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Minimal DNS wire format (RFC 1035) for A/AAAA queries used by DoH (RFC 8484). Pure JVM, unit-tested. */
object DnsWire {
    fun buildQuery(host: String, type: Int): ByteArray {
        val o = ByteArrayOutputStream()
        // id = 0 (RFC 8484 recommends it for HTTP caching), flags = RD, QDCOUNT = 1
        o.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in host.trimEnd('.').split('.')) {
            val b = java.net.IDN.toASCII(label).toByteArray()
            o.write(b.size); o.write(b)
        }
        o.write(0)
        o.write(type shr 8); o.write(type and 0xff); o.write(0); o.write(1)
        return o.toByteArray()
    }

    /** Returns addresses of the requested [type] (1 = A, 28 = AAAA) and the minimal TTL. */
    fun parseAnswers(m: ByteArray, type: Int): Pair<List<InetAddress>, Int> {
        fun u16(i: Int) = ((m[i].toInt() and 0xff) shl 8) or (m[i + 1].toInt() and 0xff)
        fun skipName(start: Int): Int {
            var i = start
            while (true) {
                val len = m[i].toInt() and 0xff
                if (len == 0) return i + 1
                if (len and 0xC0 == 0xC0) return i + 2
                i += len + 1
            }
        }
        val qd = u16(4)
        val an = u16(6)
        var p = 12
        repeat(qd) { p = skipName(p) + 4 }
        val out = mutableListOf<InetAddress>()
        var ttl = 300
        repeat(an) {
            p = skipName(p)
            val t = u16(p)
            val rttl = ((m[p + 4].toLong() and 0xff) shl 24 or ((m[p + 5].toLong() and 0xff) shl 16) or
                ((m[p + 6].toLong() and 0xff) shl 8) or (m[p + 7].toLong() and 0xff)).toInt()
            val len = u16(p + 8)
            val data = p + 10
            if (t == type && (len == 4 || len == 16)) {
                out += InetAddress.getByAddress(m.copyOfRange(data, data + len))
                ttl = minOf(ttl, rttl)
            }
            p = data + len
        }
        return out to ttl
    }
}
