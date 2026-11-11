package org.ivanivorontsov.tiho.shield

/**
 * Content-CDN exceptions and matcher policy for ТихоЩит.
 *
 * Host-level copies of AdGuard Mobile Ads (and similar) drop `$third-party` / path modifiers,
 * so first-party image/static CDNs like `avatars.mds.yandex.net` and `yastatic.net` must
 * never be blocked. Apex sites such as `mail.ru` must not be stored as suffix rules —
 * that would take down every subdomain (cloud.mail.ru, e.mail.ru) while `ad.mail.ru`
 * stays listed on its own.
 *
 * Matches [app/src/main/assets/tihoshield/allow.txt]. Pure JVM, unit-tested. No VPN/proxy.
 */
object ShieldAllowlist {
    data class Rules(val suffixes: Set<String>, val patterns: List<Regex>)

    /** Registrable consumer sites that must not be used as a suffix block rule. */
    val tooBroadApexes: Set<String> = setOf(
        "mail.ru", "bk.ru", "inbox.ru", "list.ru", "alicdn.com",
        "yandex.ru", "yandex.net", "yandex.com", "yandex.com.tr", "yandex.by", "yandex.kz",
        "yandex.uz", "yandex.com.am", "ya.ru",
        "vk.com", "vk.ru", "ok.ru",
        "google.com", "google.ru", "youtube.com", "gstatic.com", "googleusercontent.com",
    )

    private val fallbackSuffixes = setOf(
        "yastatic.net", "mds.yandex.net", "favicon.yandex.net",
        "userapi.com", "vkuservideo.net", "vkuser.net", "vk-cdn.net",
        "img.mail.ru", "filin.mail.ru",
        "fonts.gstatic.com", "www.gstatic.com", "ssl.gstatic.com", "maps.gstatic.com",
        "ytimg.com", "ggpht.com",
    )
    private val fallbackPatterns = listOf(
        Regex("""^im\d+-tub-[a-z]{2}\.yandex\.net$"""),
        Regex("""^lh\d+\.googleusercontent\.com$"""),
        Regex("""^encrypted-tbn\d+\.gstatic\.com$"""),
    )

    fun parseAllowFile(text: String): Rules {
        val suffixes = LinkedHashSet<String>()
        val patterns = ArrayList<Regex>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#re ")) {
                val spec = line.substring(4).trim()
                if (spec.isNotEmpty()) {
                    try { patterns += Regex(spec) } catch (_: Exception) { /* skip bad pattern */ }
                }
                continue
            }
            if (line.startsWith("#")) continue
            DomainMatcher.normalize(line)?.let { suffixes += it }
        }
        return Rules(suffixes, patterns)
    }

    @Volatile private var rules: Rules = Rules(emptySet(), emptyList())

    fun install(text: String) { rules = parseAllowFile(text) }

    fun current(): Rules = rules

    fun isTooBroadApex(domain: String): Boolean {
        val d = DomainMatcher.normalize(domain) ?: return false
        return d in tooBroadApexes
    }

    fun isAllowed(host: String, r: Rules = rules): Boolean {
        val h = DomainMatcher.normalize(host) ?: return false
        val pats = r.patterns + fallbackPatterns
        val suffixes = r.suffixes + fallbackSuffixes
        if (pats.any { it.matches(h) }) return true
        var cur = h
        while (true) {
            if (cur in suffixes) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
            if (cur.indexOf('.') < 0) break
        }
        return false
    }
}
