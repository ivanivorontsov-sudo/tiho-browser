package org.ivanivorontsov.tiho.ext

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.ivanivorontsov.tiho.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.ivanivorontsov.tiho.security.Net
import java.io.File

data class CatalogEntry(val name: String, val description: String, val url: String, val sha256: String)


/**
 * Extension system: built-in extensions + user scripts (Greasemonkey/Tampermonkey compatible subset).
 * Chrome Web Store (.crx / WebExtensions) is NOT supported: Android System WebView has no extension runtime.
 */
class Extensions(private val ctx: Context, private val prefs: Prefs) {
    val scripts = mutableStateListOf<UserScript>()
    private val file = File(ctx.filesDir, "userscripts.json")
    var onChanged: (() -> Unit)? = null

    fun load() {
        scripts.clear()
        if (!file.exists()) {
            // First run: install the example userscript (disabled).
            val src = ctx.assets.open("userscripts/allow-copy.user.js").bufferedReader().use { it.readText() }
            scripts += UserScript.parse(src, null, enabled = false)
            save()
            return
        }
        try {
            val arr = JSONArray(file.readText())
            var migrated = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val parsed = UserScript.parse(o.getString("code"), o.optString("url").ifBlank { null }, o.optBoolean("enabled", true))
                val rh = o.optJSONObject("require_sha256")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } ?: emptyMap()
                var s = parsed.copy(
                    requireCode = o.optString("require", ""), sha256 = o.optString("sha256", ""), requireHashes = rh,
                    trustedPayment = o.optBoolean("trusted_payment", false), trustedHash = o.optString("trusted_hash", ""),
                )
                if (s.sha256.isEmpty()) { s = s.copy(sha256 = UserScriptPin.sha256(s.code)); migrated = true } // 1.0.x → pin what is installed
                if (!UserScriptPin.intact(s)) s = s.copy(tampered = true, enabled = false)
                scripts += s
            }
            if (migrated) save()
        } catch (_: Exception) {}
    }

    private fun save() {
        val arr = JSONArray()
        scripts.forEach { s ->
            arr.put(JSONObject().put("code", s.code).put("url", s.sourceUrl ?: "").put("enabled", s.enabled).put("require", s.requireCode)
                .put("sha256", s.sha256).put("require_sha256", JSONObject(s.requireHashes))
                .put("trusted_payment", s.trustedPayment).put("trusted_hash", s.trustedHash))
        }
        file.writeText(arr.toString())
    }

    fun find(id: String): UserScript? = scripts.firstOrNull { it.id == id }

    /** Installs an already pinned script (see [download]); trust for payment pages survives only if nothing changed. */
    fun install(s: UserScript) {
        val i = scripts.indexOfFirst { it.id == s.id }
        val old = if (i >= 0) scripts[i] else null
        val keepTrust = old != null && old.trustedPayment && old.bundleHash == s.bundleHash
        val n = s.copy(trustedPayment = keepTrust, trustedHash = if (keepTrust) old!!.trustedHash else "", tampered = false)
        if (i >= 0) scripts[i] = n else scripts += n
        save(); onChanged?.invoke()
    }

    /** «Доверять на оплате»: pins the current bundle hash; any later change revokes the trust automatically. */
    fun setTrustedPayment(id: String, on: Boolean) {
        val i = scripts.indexOfFirst { it.id == id }
        if (i >= 0) { val s = scripts[i]; scripts[i] = s.copy(trustedPayment = on, trustedHash = if (on) s.bundleHash else ""); save(); onChanged?.invoke() }
    }

    fun setEnabled(id: String, on: Boolean) {
        val i = scripts.indexOfFirst { it.id == id }
        if (i >= 0) { scripts[i] = scripts[i].copy(enabled = on); save(); onChanged?.invoke() }
    }

    fun delete(id: String) {
        scripts.removeAll { it.id == id }
        ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).edit().clear().apply()
        save(); onChanged?.invoke()
    }

    /**
     * Downloads a userscript and all its @require files over HTTPS only (background thread) and pins their SHA-256.
     * Nothing is installed here: the caller shows the hashes and asks the user first.
     */
    fun download(url: String, expectedSha256: String? = null): UserScript {
        UserScriptPin.requireHttps(url)
        val text = Net.getText(url, 4L * 1024 * 1024)
        val parsed = UserScript.parse(text, url)
        if (expectedSha256 != null && UserScriptPin.sha256(text) != expectedSha256.lowercase())
            throw SecurityException("SHA-256 скрипта не совпадает с каталогом")
        val req = LinkedHashMap<String, String>()
        for (r in parsed.requires) {
            UserScriptPin.requireHttps(r) // @require over http:// would let anyone on the network inject code
            req[r] = Net.getText(r, 4L * 1024 * 1024)
        }
        return UserScriptPin.pin(parsed, req)
    }

    /** Userscript catalog from the signed content pack: [{name, description, url, sha256}]. */
    fun catalog(json: String?): List<CatalogEntry> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url").startsWith("https://") }
            .map { CatalogEntry(it.getString("name"), it.optString("description"), it.getString("url"), it.getString("sha256")) }
    } catch (_: Exception) { emptyList() }

    fun gmGet(id: String, key: String): String? = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).getString(key, null)
    fun gmSet(id: String, key: String, value: String?) {
        val e = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }
    /** Snapshot of a script's stored values (JSON strings), embedded into its wrapper for synchronous GM_getValue. */
    fun gmAll(id: String): Map<String, String> =
        ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    fun gmKeys(id: String): List<String> = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).all.keys.toList()
}
