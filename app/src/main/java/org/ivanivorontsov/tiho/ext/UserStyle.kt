package org.ivanivorontsov.tiho.ext

import java.util.Locale

/**
 * Stylus / userstyles.world / USO-compatible userstyle.
 * Supports `==UserStyle==` metadata and `@-moz-document` / `@document` blocks.
 * Pure JVM, unit-tested.
 */
data class UserStyle(
    val id: String,
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val extraRegexes: List<String> = emptyList(),
    val css: String,
    val code: String,
    val sourceUrl: String? = null,
    val enabled: Boolean = true,
    val sha256: String = "",
    val trustedPayment: Boolean = false,
    val trustedHash: String = "",
    val tampered: Boolean = false,
) {
    val bundleHash: String get() = UserScriptPin.sha256(css)
    val runsOnPayment: Boolean get() = trustedPayment && trustedHash.isNotEmpty() && trustedHash == bundleHash && !tampered

    val includeRegexes: List<String> get() = matches.map(UserScript::matchPatternToRegex) + includes.map(UserScript::globToRegex) + extraRegexes
    val excludeRegexes: List<String> get() = excludes.map(UserScript::globToRegex)

    fun appliesTo(url: String): Boolean {
        val inc = includeRegexes
        if (inc.isEmpty()) return true
        if (inc.none { Regex(it).containsMatchIn(url) }) return false
        return excludeRegexes.none { Regex(it).containsMatchIn(url) }
    }

    companion object {
        fun parse(source: String, sourceUrl: String? = null, enabled: Boolean = true): UserStyle {
            val start = source.indexOf("==UserStyle==")
            val end = source.indexOf("==/UserStyle==")
            val meta = mutableMapOf<String, MutableList<String>>()
            if (start >= 0 && end > start) {
                for (line in source.substring(start, end).lines()) {
                    val m = Regex("^\\s*(?://|/\\*)?\\s*@([\\w:.-]+)(?:\\s+(.*))?$").find(line.trim().removeSuffix("*/").trim()) ?: continue
                    meta.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2].trim()
                }
            }
            fun one(k: String) = meta[k]?.firstOrNull() ?: ""
            val name = meta["name:ru"]?.firstOrNull() ?: one("name").ifBlank { fallbackName(sourceUrl) }
            require(name.isNotBlank()) { "У стиля нет @name" }
            val ns = one("namespace")
            val docs = parseMozDocument(source)
            val matches = meta["match"].orEmpty() + docs.matches
            val includes = meta["include"].orEmpty()
            val excludes = meta["exclude"].orEmpty() + meta["exclude-match"].orEmpty()
            val css = docs.css.ifBlank { stripHeader(source, start, end) }
            require(css.isNotBlank()) { "В файле нет CSS" }
            return UserStyle(
                id = Integer.toHexString((ns + "|style|" + name).hashCode()),
                name = name, namespace = ns, version = one("version"),
                description = meta["description:ru"]?.firstOrNull() ?: one("description"),
                author = one("author"),
                matches = matches, includes = includes, excludes = excludes,
                extraRegexes = docs.regexes,
                css = css, code = source, sourceUrl = sourceUrl, enabled = enabled,
            )
        }

        private fun fallbackName(url: String?): String {
            if (url == null) return "Стиль"
            return url.substringAfterLast('/').substringBefore('?').removeSuffix(".user.css").ifBlank { "Стиль" }
        }

        private fun stripHeader(source: String, start: Int, end: Int): String {
            if (start < 0 || end < 0) return source.trim()
            val after = source.indexOf("==/UserStyle==") + "==/UserStyle==".length
            return source.substring(after).trim().removePrefix("*/").trim()
        }

        data class Docs(val matches: List<String>, val regexes: List<String>, val css: String)

        internal fun parseMozDocument(source: String): Docs {
            val re = Regex("@-(?:moz-)?document\\s+([^\\{]+)\\{", RegexOption.IGNORE_CASE)
            val matches = ArrayList<String>()
            val regexes = ArrayList<String>()
            val cssParts = ArrayList<String>()
            var found = false
            var i = 0
            while (true) {
                val m = re.find(source, i) ?: break
                found = true
                val args = m.groupValues[1]
                val blockStart = m.range.last + 1
                val blockEnd = matchingBrace(source, m.range.last)
                val body = if (blockEnd > blockStart) source.substring(blockStart, blockEnd) else ""
                cssParts += body
                parseDocumentArgs(args, matches, regexes)
                i = (if (blockEnd >= 0) blockEnd + 1 else m.range.last + 1)
            }
            return if (!found) Docs(emptyList(), emptyList(), "") else Docs(matches.distinct(), regexes.distinct(), cssParts.joinToString("\n"))
        }

        private fun matchingBrace(s: String, openIdx: Int): Int {
            var depth = 0
            var i = openIdx
            while (i < s.length) {
                val c = s[i]
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        internal fun parseDocumentArgs(args: String, matches: MutableList<String>, regexes: MutableList<String>) {
            val item = Regex("(domain|url|url-prefix|regexp)\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)", RegexOption.IGNORE_CASE)
            for (m in item.findAll(args)) {
                val kind = m.groupValues[1].lowercase(Locale.ROOT)
                val v = m.groupValues[2]
                when (kind) {
                    "domain" -> {
                        val d = v.lowercase(Locale.ROOT).trimStart('.')
                        matches += "*://$d/*"
                        matches += "*://*.$d/*"
                    }
                    "url" -> regexes += "^" + Regex.escape(v) + "$"
                    "url-prefix" -> regexes += "^" + Regex.escape(v) + ".*"
                    "regexp" -> regexes += UserScript.safeUserRegex(v)
                }
            }
        }
    }
}
