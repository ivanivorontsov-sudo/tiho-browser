package org.ivanivorontsov.tiho.security

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The app's own network requests (filter lists, userscripts, content packs, APK updates). HTTPS is enforced in code,
 * independently of network_security_config: http:// URLs and redirects to other schemes are refused.
 */
object Net {
    class InsecureUrl(u: String) : IOException("Только HTTPS: $u")

    private fun open(url: String, userAgent: String = "Mozilla/5.0"): HttpURLConnection {
        if (!url.startsWith("https://", ignoreCase = true)) throw InsecureUrl(url)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = true // HttpURLConnection never follows https → http
        c.useCaches = false
        c.setRequestProperty("User-Agent", userAgent)
        return c
    }

    private fun check(c: HttpURLConnection, url: String) {
        val code = c.responseCode
        if (!c.url.protocol.equals("https", true)) throw InsecureUrl(c.url.toString())
        if (code != 200) throw IOException("HTTP $code: $url")
    }

    fun get(url: String, maxBytes: Long = 32L * 1024 * 1024, userAgent: String = "Mozilla/5.0"): ByteArray {
        val c = open(url, userAgent)
        try {
            check(c, url)
            val out = ByteArrayOutputStream()
            c.inputStream.use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("файл больше допустимого")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    fun getText(url: String, maxBytes: Long = 32L * 1024 * 1024) = String(get(url, maxBytes), Charsets.UTF_8)

    /** Streams to [dest]; returns SHA-256 hex. */
    fun download(url: String, dest: File, maxBytes: Long, progress: (Long) -> Unit = {}): String {
        val c = open(url)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            check(c, url)
            var total = 0L
            c.inputStream.use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("файл больше допустимого")
                        md.update(buf, 0, n); o.write(buf, 0, n); progress(total)
                    }
                }
            }
        } finally { c.disconnect() }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
