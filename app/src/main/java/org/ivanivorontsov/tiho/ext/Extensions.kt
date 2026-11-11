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
 * Extension system: user scripts (Greasemonkey/Tampermonkey subset), userstyles (Stylus / .user.css),
 * and content_scripts extracted from Chrome/Firefox packages (CRX/XPI). Full AMO WebExtensions
 * are installed separately via Gecko (see Addons).
 */
class Extensions(private val ctx: Context, private val prefs: Prefs) {
    val scripts = mutableStateListOf<UserScript>()
    val styles = mutableStateListOf<UserStyle>()
    private val file = File(ctx.filesDir, "userscripts.json")
    private val styleFile = File(ctx.filesDir, "userstyles.json")
    var onChanged: (() -> Unit)? = null

    fun load() {
        scripts.clear()
        styles.clear()
        loadStyles()
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

    private fun loadStyles() {
        if (!styleFile.exists()) return
        try {
            val arr = JSONArray(styleFile.readText())
            var migrated = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var s = UserStyle.parse(o.getString("code"), o.optString("url").ifBlank { null }, o.optBoolean("enabled", true))
                s = s.copy(
                    sha256 = o.optString("sha256", ""),
                    trustedPayment = o.optBoolean("trusted_payment", false),
                    trustedHash = o.optString("trusted_hash", ""),
                )
                if (s.sha256.isEmpty()) { s = s.copy(sha256 = UserScriptPin.sha256(s.code)); migrated = true }
                if (s.sha256.isNotEmpty() && s.sha256 != UserScriptPin.sha256(s.code)) s = s.copy(tampered = true, enabled = false)
                styles += s
            }
            if (migrated) saveStyles()
        } catch (_: Exception) {}
    }

    private fun saveStyles() {
        val arr = JSONArray()
        styles.forEach { s ->
            arr.put(JSONObject().put("code", s.code).put("url", s.sourceUrl ?: "").put("enabled", s.enabled)
                .put("sha256", s.sha256).put("trusted_payment", s.trustedPayment).put("trusted_hash", s.trustedHash))
        }
        styleFile.writeText(arr.toString())
    }

    fun findStyle(id: String): UserStyle? = styles.firstOrNull { it.id == id }

    fun installStyle(s: UserStyle) {
        val pinned = if (s.sha256.isEmpty()) s.copy(sha256 = UserScriptPin.sha256(s.code)) else s
        val i = styles.indexOfFirst { it.id == pinned.id }
        val old = if (i >= 0) styles[i] else null
        val keepTrust = old != null && old.trustedPayment && old.bundleHash == pinned.bundleHash
        val n = pinned.copy(trustedPayment = keepTrust, trustedHash = if (keepTrust) old!!.trustedHash else "", tampered = false)
        if (i >= 0) styles[i] = n else styles += n
        saveStyles(); onChanged?.invoke()
    }

    fun setStyleEnabled(id: String, on: Boolean) {
        val i = styles.indexOfFirst { it.id == id }
        if (i >= 0) { styles[i] = styles[i].copy(enabled = on); saveStyles(); onChanged?.invoke() }
    }

    fun setStyleTrustedPayment(id: String, on: Boolean) {
        val i = styles.indexOfFirst { it.id == id }
        if (i >= 0) { val s = styles[i]; styles[i] = s.copy(trustedPayment = on, trustedHash = if (on) s.bundleHash else ""); saveStyles(); onChanged?.invoke() }
    }

    fun deleteStyle(id: String) {
        styles.removeAll { it.id == id }
        saveStyles(); onChanged?.invoke()
    }

    fun downloadStyle(url: String, expectedSha256: String? = null): UserStyle {
        UserScriptPin.requireHttps(url)
        val text = Net.getText(url, 4L * 1024 * 1024)
        if (expectedSha256 != null && UserScriptPin.sha256(text) != expectedSha256.lowercase())
            throw SecurityException("SHA-256 стиля не совпадает")
        val parsed = UserStyle.parse(text, url)
        return parsed.copy(sha256 = UserScriptPin.sha256(parsed.code))
    }

    fun downloadPackage(url: String, userAgent: String = "Mozilla/5.0"): Pair<WebExtPackage.Preview, ByteArray> {
        UserScriptPin.requireHttps(url)
        val bytes = Net.get(url, WebExtPackage.MAX_PACKAGE, userAgent)
        return WebExtPackage.parse(bytes) to bytes
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
