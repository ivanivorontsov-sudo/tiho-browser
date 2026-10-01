package org.ivanivorontsov.tiho.security

/**
 * Phishing / malware URL lists (malware-filter phishing-filter, URLhaus) for the top-level navigation interstitial.
 * Accepts AdGuard/ABP "||host/path^$all", hosts-file and bare-domain lines. Pure JVM (unit-tested).
 */
class ThreatList private constructor(private val hosts: Set<String>, private val paths: Map<String, List<String>>) {
    val size get() = hosts.size + paths.values.sumOf { it.size }

    data class Hit(val rule: String)

    fun match(url: String): Hit? {
        val u = try { java.net.URI(url) } catch (_: Exception) { return null }
        if (u.scheme != "http" && u.scheme != "https") return null
        var h = (u.host ?: return null).lowercase().trimEnd('.')
        val rest = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
        while (true) {
            if (h in hosts) return Hit(h)
            paths[h]?.forEach { p ->
                if (rest.startsWith(p) && (rest.length == p.length || p.endsWith("/") || rest[p.length] in "/?&=:#;")) return Hit(h + p)
            }
            val i = h.indexOf('.')
            if (i < 0) return null
            h = h.substring(i + 1)
        }
    }

    companion object {
        val EMPTY = ThreatList(emptySet(), emptyMap())

        fun parse(vararg texts: String): ThreatList {
            val hosts = HashSet<String>()
            val paths = HashMap<String, MutableList<String>>()
            for (text in texts) for (raw in text.lineSequence()) {
                var l = raw.trim()
                if (l.isEmpty() || l[0] == '!' || l[0] == '#' || l.startsWith("@@") || l.contains("##")) continue
                if (l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ")) l = l.substringAfter(' ').trim()
                l = l.removePrefix("||").substringBefore('$').removeSuffix("^").replace("&amp;", "&")
                l = l.removePrefix("https://").removePrefix("http://")
                if (l.isEmpty() || l.contains('*') || l.contains(' ')) continue
                val slash = l.indexOf('/')
                val host = (if (slash < 0) l else l.substring(0, slash)).lowercase().trimEnd('.')
                if (!host.contains('.') && !host.contains(':')) continue
                if (slash < 0) hosts += host
                else paths.getOrPut(host) { mutableListOf() } += l.substring(slash).removeSuffix("^")
            }
            return ThreatList(hosts, paths)
        }
    }
}
