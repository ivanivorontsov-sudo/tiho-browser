package org.ivanivorontsov.tiho.security

/**
 * Scheme whitelist for every URL that enters the browser from outside a web page:
 * address bar, newTab(), VIEW/SEND/WEB_SEARCH intents, bookmarks, userscript GM_openInTab.
 * Pure JVM (unit-tested).
 */
object SchemePolicy {
    enum class Source { TYPED, EXTERNAL, INTERNAL }

    sealed class Result {
        /** Safe URL to load as is. */
        data class Load(val url: String) : Result()
        /** Not a URL: treat as a search query / bare host. */
        data class Text(val text: String) : Result()
        /** Refused; [reason] is shown to the user. */
        data class Blocked(val scheme: String, val reason: String) : Result()
    }

    /** Schemes that can never be opened from outside a page (local files, other apps' providers, script). */
    val ALWAYS_BLOCKED = setOf("file", "content", "javascript", "vbscript", "chrome", "chrome-extension", "android-app", "jar", "filesystem", "view-source")
    /** Schemes we only accept from our own code, never from intents or the address bar. */
    private val INTERNAL_ONLY = setOf("data", "blob", "intent")

    private val schemeRe = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Strips whitespace and control characters (incl. bidi overrides) that browsers ignore inside schemes. */
    fun normalize(raw: String): String =
        raw.trim().filter { it >= ' ' && it != '\u007f' && it !in "\u200b\u200c\u200d\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069\ufeff" }

    fun schemeOf(raw: String): String? {
        val s = normalize(raw)
        val m = schemeRe.find(s) ?: return null
        val sch = m.groupValues[1].lowercase()
        // "localhost:8080" / "example.com:443/x" are host:port, not a scheme.
        val rest = s.substring(m.range.last + 1)
        if (rest.firstOrNull()?.isDigit() == true && sch.contains('.').not() && sch !in ALWAYS_BLOCKED && sch !in INTERNAL_ONLY && sch !in setOf("http", "https", "about")) return null
        if (sch.contains('.')) return null
        return sch
    }

    fun check(raw: String, source: Source): Result {
        val s = normalize(raw)
        if (s.isEmpty()) return Result.Text("")
        val scheme = schemeOf(s) ?: return Result.Text(s)
        return when {
            scheme == "http" || scheme == "https" -> Result.Load(s)
            scheme == "about" -> if (s.equals("about:blank", true)) Result.Load("about:blank") else Result.Blocked(scheme, "Внутренние about:-страницы недоступны")
            scheme in ALWAYS_BLOCKED -> Result.Blocked(scheme, when (scheme) {
                "javascript", "vbscript" -> "Ссылки javascript: не выполняются — так сайты и приложения подсовывают вредоносный код"
                "file" -> "Локальные файлы (file:) не открываются: так другое приложение может украсть данные"
                "content" -> "Ссылки content: других приложений не открываются"
                else -> "Схема $scheme: не поддерживается"
            })
            scheme in INTERNAL_ONLY && source == Source.INTERNAL -> Result.Load(s)
            scheme in INTERNAL_ONLY -> Result.Blocked(scheme, "Ссылки $scheme: из других приложений и адресной строки не открываются")
            // mailto:, tel:, market: etc. typed by the user or sent by an app: not a web page → treat as text/search.
            source == Source.INTERNAL -> Result.Blocked(scheme, "Схема $scheme: не поддерживается")
            else -> Result.Text(s)
        }
    }

    /** Whether a page-initiated navigation to [scheme] may stay inside the WebView. */
    fun allowedInPage(scheme: String): Boolean = scheme in setOf("http", "https", "about", "data", "blob")

    /** For VIEW intents: only http(s) URIs are accepted as URLs. */
    fun fromIntent(data: String?): Result = if (data == null) Result.Text("") else check(data, Source.EXTERNAL)

    /** For SEND / WEB_SEARCH text: first http(s) URL if present, otherwise the text as a search query. */
    fun fromSharedText(text: String?): Result {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Result.Text("")
        Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).find(t)?.let { return check(it.value, Source.EXTERNAL) }
        val r = check(t, Source.EXTERNAL)
        return if (r is Result.Blocked) r else Result.Text(t.take(2000))
    }
}
