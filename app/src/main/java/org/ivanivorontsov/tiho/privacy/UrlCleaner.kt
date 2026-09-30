package org.ivanivorontsov.tiho.privacy

import java.net.URI
import java.net.URLDecoder

/**
 * Link-tracking protection (DuckDuckGo / Brave / Firefox query-stripping style):
 *  1. unwraps redirect wrappers (google.com/url, vk.com/away.php, l.facebook.com …) and AMP URLs;
 *  2. removes known tracking parameters (utm_*, fbclid, gclid, yclid, ysclid …).
 * Pure JVM → unit-tested.
 */
object UrlCleaner {
    /** Parameters removed on every site. */
    val GLOBAL = setOf(
        // Google / DoubleClick / Analytics
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source", "gad_campaignid", "_gl", "srsltid",
        // Yandex / Russian ad networks
        "yclid", "ysclid", "_openstat", "openstat", "rb_clickid", "from_block",
        // Meta / Instagram / TikTok / X / LinkedIn / Microsoft / Pinterest / Reddit / Snap
        "fbclid", "igshid", "igsh", "ttclid", "twclid", "li_fat_id", "msclkid", "epik", "rdt_cid", "sccid", "ScCid",
        // Mailing / marketing automation
        "mc_cid", "mc_eid", "mkt_tok", "_hsenc", "_hsmi", "__hsfp", "__hssc", "__hstc", "hsCtaTracking",
        "vero_id", "vero_conv", "ml_subscriber", "ml_subscriber_hash", "oly_anon_id", "oly_enc_id", "wickedid",
        "s_cid", "soc_src", "soc_trk", "_branch_match_id", "_branch_referrer", "irclickid", "cjevent",
        "__s", "ref_src", "ref_url", "trk", "trkCampaign", "mbid", "spm", "scm", "vgo_ee", "wt_mc",
    )
    val PREFIXES = listOf("utm_", "pk_", "mtm_", "hmb_", "ga_", "stm_", "__cft", "_ke")
    /** Parameters removed only on specific sites (would break others). */
    val PER_SITE = mapOf(
        "youtube.com" to setOf("si", "pp", "feature"),
        "youtu.be" to setOf("si", "feature"),
        "open.spotify.com" to setOf("si", "context"),
        "twitter.com" to setOf("s", "t"),
        "x.com" to setOf("s", "t"),
        "instagram.com" to setOf("igsh", "img_index"),
        "amazon.com" to setOf("tag", "ref", "pd_rd_r", "pd_rd_w", "pd_rd_wg", "pf_rd_p", "pf_rd_r", "psc", "linkCode", "content-id"),
        "aliexpress.com" to setOf("spm", "scm", "algo_pvid", "algo_exp_id", "pdp_npi", "gatewayAdapt", "aff_platform", "sk", "aff_trace_key"),
        "aliexpress.ru" to setOf("spm", "scm", "algo_pvid", "algo_exp_id", "sk", "aff_trace_key"),
        "ozon.ru" to setOf("from_sku", "oos_search", "asb", "asb2", "keywords", "sh"),
        "wildberries.ru" to setOf("targetUrl", "utm_source"),
        "dzen.ru" to setOf("rid", "referrer_clid", "clid", "from", "utm_referrer"),
    )

    /** Hosts whose redirect parameter carries the real destination. */
    private val WRAPPERS: List<Pair<(String, String) -> Boolean, List<String>>> = listOf(
        { h: String, p: String -> (h.startsWith("google.") || h.contains(".google.")) && p == "/url" } to listOf("q", "url"),
        { h: String, p: String -> (h == "l.facebook.com" || h == "lm.facebook.com" || h == "m.facebook.com") && p.startsWith("/l.php") } to listOf("u"),
        { h: String, _: String -> h == "l.instagram.com" } to listOf("u"),
        { h: String, p: String -> (h == "vk.com" || h == "m.vk.com" || h == "vk.ru" || h == "m.vk.ru") && p == "/away.php" } to listOf("to"),
        { h: String, p: String -> (h == "ok.ru" || h == "m.ok.ru") && p == "/dk" } to listOf("st.link"),
        { h: String, p: String -> h.endsWith("youtube.com") && p == "/redirect" } to listOf("q"),
        { h: String, p: String -> h == "duckduckgo.com" && p.startsWith("/l/") } to listOf("uddg"),
        { h: String, p: String -> h == "steamcommunity.com" && p.startsWith("/linkfilter") } to listOf("url", "u"),
        { h: String, p: String -> h == "www.linkedin.com" && p.startsWith("/redir/redirect") } to listOf("url"),
        { h: String, p: String -> h == "out.reddit.com" } to listOf("url"),
        { h: String, p: String -> h == "away.vk.com" } to listOf("to"),
        { h: String, p: String -> h == "t.umblr.com" && p == "/redirect" } to listOf("z"),
        { h: String, p: String -> h.endsWith("disq.us") && p == "/url" } to listOf("url"),
        { h: String, p: String -> h == "slack-redir.net" && p == "/link" } to listOf("url"),
    )

    fun clean(url: String, stripParams: Boolean = true, unwrap: Boolean = true): String {
        var u = url
        if (unwrap) repeat(3) { val n = unwrapOnce(u) ?: return@repeat; u = n }
        if (stripParams) u = stripParams(u)
        return u
    }

    fun unwrapOnce(url: String): String? {
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath ?: ""
        // AMP: google.com/amp/s/example.com/page  and  example-com.cdn.ampproject.org/c/s/example.com/page
        if ((host.startsWith("www.google.") || host.startsWith("google.")) && path.startsWith("/amp/")) {
            val rest = path.removePrefix("/amp/")
            return if (rest.startsWith("s/")) "https://" + rest.removePrefix("s/") else "http://$rest"
        }
        if (host.endsWith(".cdn.ampproject.org")) {
            val m = Regex("^/[a-z]/(s/)?(.+)$").find(path) ?: return null
            return (if (m.groupValues[1].isNotEmpty()) "https://" else "http://") + m.groupValues[2]
        }
        for ((match, keys) in WRAPPERS) {
            if (!match(host, path)) continue
            val q = queryParams(uri.rawQuery)
            for (k in keys) {
                val v = q.firstOrNull { it.first == k }?.second ?: continue
                val dec = try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
                if (dec.startsWith("http://") || dec.startsWith("https://")) return dec
            }
        }
        return null
    }

    fun stripParams(url: String): String {
        val qi = url.indexOf('?')
        if (qi < 0) return url
        val hi = url.indexOf('#', qi)
        val base = url.substring(0, qi)
        val query = if (hi >= 0) url.substring(qi + 1, hi) else url.substring(qi + 1)
        val frag = if (hi >= 0) url.substring(hi) else ""
        val host = try { URI(base).host?.lowercase() } catch (_: Exception) { null } ?: ""
        val site = PER_SITE.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value ?: emptySet()
        val parts = query.split('&').filter { it.isNotEmpty() }
        val kept = parts.filter { p ->
            val k = p.substringBefore('=')
            val kd = try { URLDecoder.decode(k, "UTF-8") } catch (_: Exception) { k }
            !(kd in GLOBAL || kd in site || PREFIXES.any { kd.startsWith(it, ignoreCase = true) })
        }
        if (kept.size == parts.size) return url
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + frag
    }

    private fun queryParams(raw: String?): List<Pair<String, String>> =
        raw?.split('&')?.filter { it.isNotEmpty() }?.map { it.substringBefore('=') to it.substringAfter('=', "") } ?: emptyList()

    /** Registrable-domain approximation (eTLD+1) good enough for "same site?" checks. */
    fun site(host: String?): String {
        if (host == null) return ""
        val h = host.lowercase().trimEnd('.')
        if (h.matches(Regex("^[0-9.]+$")) || h.contains(':')) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val sld2 = labels[labels.size - 2]
        val multi = setOf("co", "com", "org", "net", "gov", "edu", "ac", "msk", "spb", "nov", "kiev")
        return if (sld2 in multi && labels.last().length == 2) labels.takeLast(3).joinToString(".") else labels.takeLast(2).joinToString(".")
    }
}
