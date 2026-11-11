package org.ivanivorontsov.tiho.shield

import java.util.Locale

/**
 * Suffix domain matcher used by ТихоЩит (same rules as the standalone Android VPN filter,
 * but here it only decides about in-browser request hosts — no VPN, no proxy, no IPs).
 * Pure JVM, unit-tested.
 */
class DomainMatcher {
    data class Hit(val rule: String, val list: String)

    private val blocked = HashMap<String, String>()

    fun add(domain: String, list: String) {
        val d = normalize(domain) ?: return
        blocked.putIfAbsent(d, list)
    }

    fun addAll(domains: Iterable<String>, list: String) {
        for (d in domains) add(d, list)
    }

    fun size(): Int = blocked.size

    fun match(host: String): Hit? {
        var cur = normalize(host) ?: return null
        while (true) {
            val list = blocked[cur]
            if (list != null) return Hit(cur, list)
            val dot = cur.indexOf('.')
            if (dot < 0 || dot == cur.length - 1) break
            cur = cur.substring(dot + 1)
            if (cur.indexOf('.') < 0) {
                val tld = blocked[cur]
                return if (tld != null) Hit(cur, tld) else null
            }
        }
        return null
    }

    companion object {
        fun normalize(host: String?): String? {
            if (host == null) return null
            var h = host.trim().lowercase(Locale.ROOT)
            if (h.endsWith(".")) h = h.dropLast(1)
            if (h.startsWith(".")) h = h.drop(1)
            if (h.isEmpty() || h.indexOf('.') < 0) return null
            return h
        }

        /** Parses a Щит-style domain list (comments, hosts(5), `||domain^`, bare domains). IPs/CIDR ignored. */
        fun parseDomains(text: String): Set<String> {
            val out = HashSet<String>()
            val abp = Regex("^\\|\\|([^\\^\\$\\*/]+)(?:\\^|\\$|$)")
            val hosts = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+([A-Za-z0-9._-]+)\\s*$")
            val domain = Regex("^[a-z0-9._-]+\\.[a-z0-9.-]+$")
            val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
            val cidr = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}/\\d{1,2}$")
            for (raw in text.split('\n')) {
                var line = raw.trim()
                if (line.isEmpty()) continue
                val c0 = line[0]
                if (c0 == '#' || c0 == '!' || c0 == '[') continue
                if (line.startsWith("@@")) continue
                val a = abp.find(line)
                if (a != null) { normalize(a.groupValues[1])?.let { out += it }; continue }
                val ho = hosts.matchEntire(line)
                if (ho != null) { normalize(ho.groupValues[1])?.let { out += it }; continue }
                val hash = line.indexOf('#')
                if (hash > 0) line = line.substring(0, hash).trim()
                val lower = line.lowercase(Locale.ROOT)
                if (ipv4.matches(lower) || cidr.matches(lower)) continue
                if (domain.matches(lower)) normalize(lower)?.let { out += it }
            }
            return out
        }
    }
}

object ShieldLists {
    data class Meta(val id: String, val title: String, val description: String, val defaultOn: Boolean)

    const val GOOGLE = "google-ads"
    const val SDK = "sdk-ads"
    const val ADGUARD = "adguard-mobile"
    const val HAGEZI = "hagezi-promini"
    const val TRACKING = "tracking"

    val ALL = listOf(
        Meta(GOOGLE, "Google Ads / AdMob / IMA", "DoubleClick, AdMob, IMA и видео-межстраничная реклама Google", true),
        Meta(SDK, "Мобильные SDK", "Unity Ads, AppLovin, ironSource, Vungle, Pangle, Chartboost, BigoAds, Start.io и другие", true),
        Meta(ADGUARD, "AdGuard Mobile Ads", "Домены из фильтра AdGuard Mobile Ads (CC BY-SA 3.0)", true),
        Meta(HAGEZI, "HaGeZi Pro mini", "Крупный доменный список HaGeZi Multi PRO mini", true),
        Meta(TRACKING, "Аналитика и measurement", "Adjust, Amplitude, app-measurement.com и похожие (как «Агрессивно» в ТихоЩит)", false),
    )

    fun ids() = ALL.map { it.id }

    fun parseEnabled(stored: String): Map<String, Boolean> {
        val on = stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // Empty stored → defaults. A single "-" means every list is off.
        if (stored.isEmpty()) return ALL.associate { it.id to it.defaultOn }
        return ALL.associate { it.id to (it.id in on) }
    }

    fun serializeEnabled(on: Map<String, Boolean>): String {
        val ids = ALL.map { it.id }.filter { on[it] == true }
        return if (ids.isEmpty()) "-" else ids.joinToString(",")
    }
}
