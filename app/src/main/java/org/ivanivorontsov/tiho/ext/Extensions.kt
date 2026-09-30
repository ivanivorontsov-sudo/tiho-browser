package org.ivanivorontsov.tiho.ext

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.ivanivorontsov.tiho.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Built-in extension (toggle only). */
data class BuiltinExt(val id: String, val title: String, val description: String, val prefKey: String, val default: Boolean)

/**
 * Extension system: built-in extensions + user scripts (Greasemonkey/Tampermonkey compatible subset).
 * Chrome Web Store (.crx / WebExtensions) is NOT supported: Android System WebView has no extension runtime.
 */
class Extensions(private val ctx: Context, private val prefs: Prefs) {
    val scripts = mutableStateListOf<UserScript>()
    private val file = File(ctx.filesDir, "userscripts.json")
    var onChanged: (() -> Unit)? = null

    val builtins = listOf(
        BuiltinExt("adblock", "Блокировщик рекламы и трекеров", "Brave adblock-rust: EasyList, EasyPrivacy, RU AdList, uBlock, Peter Lowe, AdGuard", "adblock", true),
        BuiltinExt("cookies", "Без cookie-баннеров", "Скрывает баннеры согласия и нажимает «Отклонить» (в духе I don't care about cookies)", "ext_cookies", true),
        BuiltinExt("youtube", "YouTube без рекламы", "Скриптлеты uBlock + пропуск/перемотка рекламных роликов и скрытие промо-блоков", "ext_youtube", true),
        BuiltinExt("dark", "Тёмные сайты", "Принудительная тёмная тема для светлых сайтов (Dark Reader lite)", "ext_dark", false),
    )

    fun builtinEnabled(b: BuiltinExt): Boolean = if (b.id == "adblock") prefs.adblock else prefs.sp.getBoolean(b.prefKey, b.default)

    fun setBuiltin(b: BuiltinExt, on: Boolean) {
        if (b.id == "adblock") prefs.adblock = on else prefs.sp.edit().putBoolean(b.prefKey, on).apply()
        onChanged?.invoke()
    }

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
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = UserScript.parse(o.getString("code"), o.optString("url").ifBlank { null }, o.optBoolean("enabled", true))
                scripts += s.copy(requireCode = o.optString("require", ""))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val arr = JSONArray()
        scripts.forEach { s ->
            arr.put(JSONObject().put("code", s.code).put("url", s.sourceUrl ?: "").put("enabled", s.enabled).put("require", s.requireCode))
        }
        file.writeText(arr.toString())
    }

    fun install(s: UserScript) {
        val withReq = if (s.requires.isNotEmpty() && s.requireCode.isEmpty()) s.copy(requireCode = fetchRequires(s.requires)) else s
        val i = scripts.indexOfFirst { it.id == s.id }
        if (i >= 0) scripts[i] = withReq else scripts += withReq
        save(); onChanged?.invoke()
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

    /** Downloads a userscript (runs on a background thread). */
    fun download(url: String): UserScript {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return UserScript.parse(text, url)
    }

    private fun fetchRequires(urls: List<String>): String = urls.joinToString("\n;\n") { u ->
        try { (URL(u).openConnection() as HttpURLConnection).inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
    }

    fun gmGet(id: String, key: String): String? = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).getString(key, null)
    fun gmSet(id: String, key: String, value: String?) {
        val e = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }
    fun gmKeys(id: String): List<String> = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).all.keys.toList()
}
