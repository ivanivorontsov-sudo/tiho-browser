package org.ivanivorontsov.tiho.privacy

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * DNS-over-HTTPS for WebView.
 *
 * WebView has no resolver API, but androidx.webkit ProxyController can route all WebView traffic
 * through a proxy. This is a small local HTTP/CONNECT proxy bound to 127.0.0.1 that resolves each
 * hostname with DoH (RFC 8484) and then only pipes bytes. HTTPS stays end-to-end encrypted between
 * WebView and the site (CONNECT tunnel), so the proxy never sees HTTPS page content.
 */
class DohProxy(private val endpoint: String, private val extraHeaders: () -> List<String>) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    fun start(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun readHead(inp: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 65536) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return buf.toByteArray()
        }
        return null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 120000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val head = readHead(cin) ?: return client.close()
            val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
            val first = lines[0].split(' ')
            val method = first[0]
            val target = first.getOrElse(1) { "" }
            if (method.equals("CONNECT", true)) {
                val host = target.substringBeforeLast(':').trim('[', ']')
                val port = target.substringAfterLast(':').toIntOrNull() ?: 443
                val up = connect(host, port)
                if (up == null) { cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return client.close() }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cout.flush()
                pipe(client, up)
            } else {
                // Plain HTTP with absolute URI: rewrite to origin-form, add DNT/GPC, forward one request.
                val u = URL(target)
                val up = connect(u.host, if (u.port > 0) u.port else 80)
                if (up == null) { cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return client.close() }
                val sb = StringBuilder()
                sb.append(method).append(' ').append(u.file.ifEmpty { "/" }).append(' ').append(first.getOrElse(2) { "HTTP/1.1" }).append("\r\n")
                for (l in lines.drop(1)) {
                    if (l.isEmpty()) continue
                    val k = l.substringBefore(':').lowercase()
                    if (k == "proxy-connection" || k == "connection" || k == "keep-alive") continue
                    sb.append(l).append("\r\n")
                }
                extraHeaders().forEach { sb.append(it).append("\r\n") }
                sb.append("Connection: close\r\n\r\n")
                up.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                pipe(client, up)
            }
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun connect(host: String, port: Int): Socket? {
        for (a in resolve(host)) {
            try {
                return Socket().apply { connect(InetSocketAddress(a, port), 10000); tcpNoDelay = true }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun pipe(a: Socket, b: Socket) {
        val t = Thread { copy(b.getInputStream(), a.getOutputStream()); closeAll(a, b) }
        t.isDaemon = true
        t.start()
        copy(a.getInputStream(), b.getOutputStream())
        closeAll(a, b)
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n); o.flush()
            }
        } catch (_: Exception) {}
    }

    private fun closeAll(vararg s: Socket) = s.forEach { try { it.close() } catch (_: Exception) {} }

    fun resolve(host: String): List<InetAddress> {
        if (host == "localhost") return listOf(InetAddress.getByName("127.0.0.1"))
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(':')) return listOf(InetAddress.getByName(host))
        cache[host]?.let { (a, exp) -> if (exp > System.currentTimeMillis()) return a }
        val res = mutableListOf<InetAddress>()
        var ttl = 300
        for (type in intArrayOf(1, 28)) {
            try {
                val (addrs, t) = query(host, type)
                res += addrs
                if (addrs.isNotEmpty()) ttl = minOf(ttl, maxOf(t, 30))
            } catch (e: Exception) {
                Log.w("Tiho", "DoH $host/$type failed: ${e.message}")
            }
            if (res.isNotEmpty()) break
        }
        if (res.isNotEmpty()) cache[host] = res to (System.currentTimeMillis() + ttl * 1000L)
        return res
    }

    private fun query(host: String, type: Int): Pair<List<InetAddress>, Int> {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "application/dns-message")
        c.setRequestProperty("Accept", "application/dns-message")
        c.outputStream.use { it.write(DnsWire.buildQuery(host, type)) }
        val body = c.inputStream.use { it.readBytes() }
        return DnsWire.parseAnswers(body, type)
    }

    companion object {
        fun endpointFor(id: String) = when (id) {
            "cloudflare" -> "https://cloudflare-dns.com/dns-query"
            "adguard" -> "https://dns.adguard-dns.com/dns-query"
            "mullvad" -> "https://dns.mullvad.net/dns-query"
            else -> "https://dns.quad9.net/dns-query"
        }
    }
}
