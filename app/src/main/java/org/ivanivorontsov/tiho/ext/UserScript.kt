package org.ivanivorontsov.tiho.ext

/**
 * Greasemonkey/Tampermonkey userscript model + metadata parser + @match/@include matcher.
 * Pure JVM (unit-tested). Match patterns are compiled to regexes that work identically in Java and JS.
 */
data class UserScript(
    val id: String,
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val excludeMatches: List<String> = emptyList(),
    val runAt: String = "document-end",
    val noframes: Boolean = false,
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val code: String,
    val requireCode: String = "",
    val sourceUrl: String? = null,
    val enabled: Boolean = true,
) {
    /** Regexes (JS-compatible source strings) for URLs the script runs on / must skip. */
    val includeRegexes: List<String> get() = matches.map(::matchPatternToRegex) + includes.map(::globToRegex)
    val excludeRegexes: List<String> get() = excludeMatches.map(::matchPatternToRegex) + excludes.map(::globToRegex)

    fun appliesTo(url: String): Boolean {
        val inc = includeRegexes
        if (inc.isEmpty()) return false
        if (inc.none { Regex(it).containsMatchIn(url) }) return false
        return excludeRegexes.none { Regex(it).containsMatchIn(url) }
    }

    companion object {
        fun parse(source: String, sourceUrl: String? = null, enabled: Boolean = true): UserScript {
            val start = source.indexOf("==UserScript==")
            val end = source.indexOf("==/UserScript==")
            require(start >= 0 && end > start) { "Нет блока ==UserScript==" }
            val meta = mutableMapOf<String, MutableList<String>>()
            for (line in source.substring(start, end).lines()) {
                val m = Regex("^\\s*//\\s*@([\\w:.-]+)(?:\\s+(.*))?$").find(line) ?: continue
                meta.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2].trim()
            }
            fun one(k: String) = meta[k]?.firstOrNull() ?: ""
            val name = meta["name:ru"]?.firstOrNull() ?: one("name")
            require(name.isNotBlank()) { "У скрипта нет @name" }
            val ns = one("namespace")
            val runAt = when (one("run-at").lowercase()) {
                "document-start" -> "document-start"
                "document-idle", "context-menu" -> "document-idle"
                else -> "document-end"
            }
            return UserScript(
                id = Integer.toHexString((ns + "|" + name).hashCode()),
                name = name, namespace = ns, version = one("version"),
                description = meta["description:ru"]?.firstOrNull() ?: one("description"),
                author = one("author"),
                matches = meta["match"].orEmpty(), includes = meta["include"].orEmpty(),
                excludes = meta["exclude"].orEmpty(), excludeMatches = meta["exclude-match"].orEmpty(),
                runAt = runAt, noframes = meta.containsKey("noframes"),
                grants = meta["grant"].orEmpty(), requires = meta["require"].orEmpty(),
                code = source, sourceUrl = sourceUrl, enabled = enabled,
            )
        }

        private fun esc(s: String) = s.replace(Regex("[.+?^\${}()|\\[\\]\\\\/]"), "\\\\$0")

        /** Chrome/Tampermonkey match pattern: scheme://host/path with * wildcards. */
        fun matchPatternToRegex(p: String): String {
            if (p == "<all_urls>" || p == "*") return "^(https?|file|ftp)://"
            val m = Regex("^(\\*|https?|file|ftp|wss?)://([^/]*)(/.*)?$").find(p) ?: return globToRegex(p)
            val scheme = if (m.groupValues[1] == "*") "https?" else esc(m.groupValues[1])
            val h = m.groupValues[2]
            val host = when {
                h == "*" -> "[^/]*"
                h.startsWith("*.") -> "(?:[^/]*\\.)?" + esc(h.substring(2))
                else -> esc(h)
            }.let { it.replace(":*", "(?::\\d+)?") }
            val path = (m.groupValues[3].ifEmpty { "/" }).split("*").joinToString(".*") { esc(it) }
            return "^$scheme://$host$path$"
        }

        /** @include glob (or /regex/). */
        fun globToRegex(g: String): String {
            if (g.length > 2 && g.startsWith("/") && g.endsWith("/")) return g.substring(1, g.length - 1)
            if (g == "*") return "^"
            return "^" + g.split("*").joinToString(".*") { esc(it) }.replace("\\.tld", "\\.[a-z.]{2,6}") + "$"
        }
    }
}
