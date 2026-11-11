package org.ivanivorontsov.tiho.ext

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * URL builders and JSON parsers for extension / userscript / userstyle catalogues.
 * No network I/O — unit-tested.
 */
object StoreCatalog {
    data class ScriptHit(val name: String, val description: String, val url: String, val codeUrl: String, val users: Long, val version: String)
    data class StyleHit(val name: String, val description: String, val url: String, val codeUrl: String, val id: String)
    data class StoreRef(val store: String, val id: String)

    private val CHROME_ID = Regex("^[a-p]{32}$")

    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun chromeIdFromUrl(url: String): StoreRef? {
        val u = url.trim()
        Regex("(?:chromewebstore\\.google\\.com|chrome\\.google\\.com/webstore)/(?:detail|s)/[^/\\s?#]+/([a-p]{32})", RegexOption.IGNORE_CASE)
            .find(u)?.let { return StoreRef("chrome", it.groupValues[1]) }
        Regex("(?:chromewebstore\\.google\\.com|chrome\\.google\\.com/webstore)/(?:detail|s)/([a-p]{32})(?:[/?#]|$)", RegexOption.IGNORE_CASE)
            .find(u)?.let { return StoreRef("chrome", it.groupValues[1]) }
        Regex("microsoftedge\\.microsoft\\.com/addons/detail/[^/\\s?#]+/([a-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(u)?.let { return StoreRef("edge", it.groupValues[1]) }
        Regex("addons\\.opera\\.com/[^\\s]*extensions/details/([^/?#]+)", RegexOption.IGNORE_CASE)
            .find(u)?.let { return StoreRef("opera", it.groupValues[1]) }
        if (CHROME_ID.matches(u)) return StoreRef("chrome", u)
        return null
    }

    fun crxUrl(ref: StoreRef): String = when (ref.store) {
        "edge" -> "https://edge.microsoft.com/extensionwebstorebase/v1/crx?response=redirect&x=id%3D${enc(ref.id)}%26installsource%3Dondemand%26uc"
        else -> "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=131.0.6778.0&acceptformat=crx2,crx3&x=id%3D${enc(ref.id)}%26uc"
    }

    fun greasyForkSearchUrl(q: String) =
        "https://greasyfork.org/ru/scripts.json?q=${enc(q)}&limit=25"

    fun openUserJsSearchUrl(q: String) =
        "https://openuserjs.org/?q=${enc(q)}"

    fun userstylesSearchUrl(q: String) =
        "https://userstyles.world/api/search/styles?q=${enc(q)}"

    fun parseGreasyFork(json: String): List<ScriptHit> {
        val root: JSONArray = try {
            when (val v = JSONObject("{\"x\":$json}").get("x")) {
                is JSONArray -> v
                is JSONObject -> v.optJSONArray("results") ?: JSONArray().put(v)
                else -> JSONArray()
            }
        } catch (_: Exception) {
            try { JSONObject(json).optJSONArray("results") ?: JSONArray() } catch (_: Exception) { JSONArray() }
        }
        val out = ArrayList<ScriptHit>()
        for (i in 0 until root.length()) {
            val o = root.optJSONObject(i) ?: continue
            val code = o.optString("code_url").ifBlank { o.optString("url") + ".user.js" }
            if (!code.startsWith("https://")) continue
            out += ScriptHit(
                name = o.optString("name"),
                description = o.optString("description"),
                url = o.optString("url"),
                codeUrl = code,
                users = o.optLong("users", o.optLong("total_installs")),
                version = o.optString("version"),
            )
        }
        return out
    }

    fun parseUserstylesWorld(json: String): List<StyleHit> {
        val arr: JSONArray = try {
            val o = JSONObject(json)
            o.optJSONArray("data") ?: o.optJSONArray("styles") ?: o.optJSONArray("results") ?: JSONArray()
        } catch (_: Exception) {
            try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        }
        val out = ArrayList<StyleHit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.opt("id")?.toString() ?: "" }
            if (id.isEmpty()) continue
            val page = o.optString("url").ifBlank { "https://userstyles.world/style/$id" }
            val code = when {
                o.optString("css_url").startsWith("https://") -> o.getString("css_url")
                else -> "https://userstyles.world/api/style/$id.user.css"
            }
            out += StyleHit(o.optString("display_name").ifBlank { o.optString("name") }, o.optString("description"), page, code, id)
        }
        return out
    }

    fun isUserCssUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".user.css", ignoreCase = true)
    }

    fun isCrxOrXpiUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".crx", ignoreCase = true) || path.endsWith(".xpi", ignoreCase = true)
    }
}
