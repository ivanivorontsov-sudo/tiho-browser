package org.ivanivorontsov.tiho

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All user settings. Every property is backed by SharedPreferences *and* a Compose state, so the UI
 * recomposes on change. Defaults are the "maximum privacy that does not break the web" profile.
 */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("tiho_prefs", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners += l }
    /** Keys that are statistics, not settings: no reconfiguration on change. */
    private val silent = setOf("total_blocked", "last_filter_update")
    private fun changed(key: String) { if (key !in silent) listeners.forEach { it() } }

    private inner class B(val key: String, def: Boolean) : ReadWriteProperty<Any, Boolean> {
        val st: MutableState<Boolean> = mutableStateOf(sp.getBoolean(key, def))
        override fun getValue(thisRef: Any, property: KProperty<*>) = st.value
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) {
            if (st.value == value) return
            st.value = value; sp.edit().putBoolean(key, value).apply(); changed(key)
        }
    }
    private inner class I(val key: String, def: Int) : ReadWriteProperty<Any, Int> {
        val st: MutableState<Int> = mutableStateOf(sp.getInt(key, def))
        override fun getValue(thisRef: Any, property: KProperty<*>) = st.value
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) {
            if (st.value == value) return
            st.value = value; sp.edit().putInt(key, value).apply(); changed(key)
        }
    }
    private inner class S(val key: String, def: String) : ReadWriteProperty<Any, String> {
        val st: MutableState<String> = mutableStateOf(sp.getString(key, def) ?: def)
        override fun getValue(thisRef: Any, property: KProperty<*>) = st.value
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) {
            if (st.value == value) return
            st.value = value; sp.edit().putString(key, value).apply(); changed(key)
        }
    }
    private inner class SS(val key: String, def: Set<String>) : ReadWriteProperty<Any, Set<String>> {
        val st: MutableState<Set<String>> = mutableStateOf(sp.getStringSet(key, def)?.toSet() ?: def)
        override fun getValue(thisRef: Any, property: KProperty<*>) = st.value
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Set<String>) {
            if (st.value == value) return
            st.value = value; sp.edit().putStringSet(key, value).apply(); changed(key)
        }
    }

    // --- General
    var searchEngine by S("search", "ddg")
    var theme by I("theme", 0) // 0 system, 1 light, 2 dark
    var javascript by B("js", true)
    var desktopDefault by B("desktop_default", false)
    var flagSecure by B("flag_secure", false)
    var textZoom by I("text_zoom", 100)

    // --- Data / private mode
    var clearOnExit by B("clear_on_exit", true)
    var clearOnTabClose by B("clear_on_tab_close", true)
    var incognitoDefault by B("incognito_default", false)
    var cookies by B("cookies", true)
    var thirdPartyCookies by B("third_party_cookies", false)

    // --- Network privacy
    var httpsOnly by B("https_only", true)
    var stripParams by B("strip_params", true)
    var unwrapRedirects by B("unwrap_redirects", true)
    var dnt by B("dnt", true)
    var gpc by B("gpc", true)
    var strictReferrer by B("strict_referrer", false)
    var blockPings by B("block_pings", true)
    var doh by B("doh", false)
    var dohProvider by S("doh_provider", "quad9")

    // --- Anti-fingerprinting
    var antiFp by B("anti_fp", true)
    var genericUa by B("generic_ua", true)
    var webrtcMode by I("webrtc", 0) // 0 disabled, 1 relay only (hide IP), 2 allowed
    var blockWebGl by B("block_webgl", false)
    var blockSensors by B("block_sensors", true)
    var spoofTimezone by B("spoof_tz", false)
    var drm by B("drm", false)

    // --- Content
    var adblock by B("adblock", true)
    var blockPopups by B("block_popups", true)
    var blockAutoRedirects by B("block_auto_redirects", false)
    var allowlist by SS("allowlist", emptySet())
    var enabledLists by SS("lists", DEFAULT_LISTS)
    var customRules by S("custom_rules", "")
    var extraListUrls by S("extra_list_urls", "")
    var lastFilterUpdate by S("last_filter_update", "")
    var totalBlocked by I("total_blocked", 0)

    fun searchUrl(q: String): String {
        val e = java.net.URLEncoder.encode(q, "UTF-8")
        return when (searchEngine) {
            "yandex" -> "https://yandex.ru/search/?text=$e"
            "google" -> "https://www.google.com/search?q=$e"
            "startpage" -> "https://www.startpage.com/do/search?q=$e"
            "brave" -> "https://search.brave.com/search?q=$e"
            "mojeek" -> "https://www.mojeek.com/search?q=$e"
            else -> "https://duckduckgo.com/?q=$e&kl=ru-ru"
        }
    }

    companion object {
        val DEFAULT_LISTS = setOf(
            "easylist", "easyprivacy", "ruadlist", "peterlowe", "ublock-filters", "ublock-privacy",
            "ublock-unbreak", "ublock-quickfixes", "ublock-badware", "adguard-urltracking", "cname-ads", "hagezi-light",
        )
        val SEARCH_ENGINES = listOf(
            "ddg" to "DuckDuckGo", "brave" to "Brave Search", "startpage" to "Startpage",
            "mojeek" to "Mojeek", "yandex" to "Яндекс", "google" to "Google",
        )
        val DOH_PROVIDERS = listOf(
            "quad9" to "Quad9 (Швейцария, без логов)",
            "cloudflare" to "Cloudflare 1.1.1.1",
            "adguard" to "AdGuard DNS (с блокировкой рекламы)",
            "mullvad" to "Mullvad DNS",
        )
    }
}
