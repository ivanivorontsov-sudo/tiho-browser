package org.ivanivorontsov.tiho.gecko

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure logic for WebExtensions: install pins, payment allowlist, AMO API parsing. Unit-tested.
 *
 * Pin = what we installed: add-on id + version + SHA-256 of the exact signed XPI file (bundled asset or AMO download
 * whose hash matched the AMO API). Payment trust = "id → version|sha" copied from the pin when the user trusts it.
 * Trust is valid only while the installed add-on still has that very version and pin hash; any update (or an
 * install we could not hash) silently drops the add-on back into quarantine until the user re-confirms.
 */
object AddonPolicy {
    data class Pin(val version: String, val sha256: String, val source: String)

    const val UBO_ID = "uBlock0@raymondhill.net"
    const val TIHO_EXT_ID = "tiho@ivanivorontsov.org"

    fun parsePins(json: String): MutableMap<String, Pin> {
        val out = LinkedHashMap<String, Pin>()
        try {
            val o = JSONObject(json)
            for (k in o.keys()) { val p = o.getJSONObject(k); out[k] = Pin(p.optString("v"), p.optString("sha"), p.optString("src")) }
        } catch (_: Exception) {}
        return out
    }

    fun pinsJson(p: Map<String, Pin>): String {
        val o = JSONObject()
        for ((k, v) in p) o.put(k, JSONObject().put("v", v.version).put("sha", v.sha256).put("src", v.source))
        return o.toString()
    }

    fun parseTrust(json: String): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        try { val o = JSONObject(json); for (k in o.keys()) out[k] = o.getString(k) } catch (_: Exception) {}
        return out
    }

    fun trustJson(t: Map<String, String>): String = JSONObject(t as Map<*, *>).toString()

    fun trustToken(p: Pin) = "${p.version}|${p.sha256}"

    /** Can this add-on be trusted at all? Needs a pin with a real hash for the version that is installed now. */
    fun trustable(id: String, installedVersion: String?, pins: Map<String, Pin>): Boolean {
        val p = pins[id] ?: return false
        return installedVersion != null && p.version == installedVersion && Regex("^[0-9a-f]{64}$").matches(p.sha256)
    }

    /** Ids whose trust is currently valid. */
    fun validTrusted(trust: Map<String, String>, pins: Map<String, Pin>, installed: Map<String, String>): Set<String> =
        trust.filter { (id, tok) -> trustable(id, installed[id], pins) && trustToken(pins.getValue(id)) == tok }.keys

    /** Ids that are trusted by the user but whose pin no longer matches (updated / replaced add-on). */
    fun brokenTrust(trust: Map<String, String>, pins: Map<String, Pin>, installed: Map<String, String>): Set<String> =
        trust.keys.filter { it in installed } .toSet() - validTrusted(trust, pins, installed)

    // ---------------------------------------------------------------- AMO
    data class AmoAddon(
        val guid: String, val name: String, val slug: String, val summary: String, val version: String,
        val fileUrl: String, val sha256: String, val size: Long, val recommended: Boolean, val users: Long, val iconUrl: String?,
    )

    const val AMO_API = "https://addons.mozilla.org/api/v5/addons/"
    private val AMO_HOSTS = setOf("addons.mozilla.org", "addons.cdn.mozilla.net")

    fun isAmoFileUrl(url: String): Boolean = try {
        val u = java.net.URI(url); u.scheme == "https" && u.host in AMO_HOSTS
    } catch (_: Exception) { false }

    private fun loc(o: JSONObject?, key: String): String {
        val v = o?.opt(key) ?: return ""
        if (v is String) return v
        if (v is JSONObject) return v.optString("ru").ifEmpty { v.optString("en-US") }.ifEmpty { v.optString(v.optString("_default")) }
            .ifEmpty { v.keys().asSequence().firstOrNull()?.let { v.optString(it) } ?: "" }
        return ""
    }

    /** Parses /api/v5/addons/addon/{id}/ (or one search result). Throws if the file is not a pinned HTTPS AMO download. */
    fun parseAddon(o: JSONObject): AmoAddon {
        val cv = o.getJSONObject("current_version")
        val f = cv.getJSONObject("file")
        val url = f.getString("url")
        require(isAmoFileUrl(url)) { "файл дополнения не с addons.mozilla.org" }
        val hash = f.optString("hash")
        require(hash.startsWith("sha256:") && Regex("^[0-9a-f]{64}$").matches(hash.substring(7))) { "AMO не сообщил SHA-256" }
        val promoted = o.opt("promoted")
        val recommended = when (promoted) {
            is JSONObject -> promoted.optString("category") == "recommended"
            is JSONArray -> (0 until promoted.length()).any { promoted.optJSONObject(it)?.optString("category") == "recommended" }
            else -> false
        }
        return AmoAddon(
            guid = o.getString("guid"), name = loc(o, "name"), slug = o.optString("slug"), summary = loc(o, "summary"),
            version = cv.optString("version"), fileUrl = url, sha256 = hash.substring(7), size = f.optLong("size"),
            recommended = recommended, users = o.optLong("average_daily_users"), iconUrl = o.optString("icon_url").ifEmpty { null },
        )
    }

    fun parseSearch(json: String): List<AmoAddon> {
        val a = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<AmoAddon>()
        for (i in 0 until a.length()) try { out += parseAddon(a.getJSONObject(i)) } catch (_: Exception) {}
        return out
    }

    fun searchUrl(q: String) = AMO_API + "search/?app=android&type=extension&lang=ru&sort=users&page_size=25&q=" +
        java.net.URLEncoder.encode(q, "UTF-8")
    fun featuredUrl() = AMO_API + "search/?app=android&type=extension&lang=ru&sort=users&promoted=recommended&page_size=30"
    fun addonUrl(idOrSlug: String) = AMO_API + "addon/" + java.net.URLEncoder.encode(idOrSlug, "UTF-8") + "/?app=android&lang=ru"

    /** Listing slug from an AMO page URL like https://addons.mozilla.org/ru/android/addon/darkreader/ */
    fun slugFromListing(url: String?): String? {
        if (url == null) return null
        val m = Regex("^https://addons\\.mozilla\\.org/(?:[^/]+/)?(?:android/|firefox/)?addon/([^/?#]+)").find(url) ?: return null
        return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
    }

    fun isXpiUrl(url: String): Boolean = try {
        val u = java.net.URI(url); u.scheme == "https" && (u.path ?: "").endsWith(".xpi") && u.host in AMO_HOSTS
    } catch (_: Exception) { false }
}
