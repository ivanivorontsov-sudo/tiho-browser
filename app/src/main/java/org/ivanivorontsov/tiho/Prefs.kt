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
    private val silent = setOf("total_blocked", "content_status", "content_last_check", "payment_known", "app_update_status",
        "app_last_check", "addon_pins", "addons_paused", "ubo_installed", "update_banner_dismissed", "app_last_ok")
    fun changed(key: String) { if (key !in silent) listeners.forEach { it() } }

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
    var desktopDefault by B("desktop_default", false)
    /** 0 = top tab strip, 1 = bottom tab strip, 2 = classic tabs button only. */
    var tabBarPosition by I("tab_bar_position", 0)
    /** 0 = Vivaldi cards, 1 = compact cards, 2 = favicons, 3 = text tabs, 4 = minimal underline. */
    var tabBarStyle by I("tab_bar_style", 0)
    var flagSecure by B("flag_secure", false)

    // --- Data / private mode
    var clearOnExit by B("clear_on_exit", true)
    /** Browsing history: off by default; never recorded in private / «Банк» tabs. */
    var historyEnabled by B("history_enabled", false)
    /** Auto-delete history older than N days (0 = never). */
    var historyDays by I("history_days", 0)
    var incognitoDefault by B("incognito_default", false)

    // --- Gecko privacy (Arkenfox / IronFox-style prefs)
    var httpsOnly by B("https_only", true)
    var etpStrict by B("etp_strict", true)
    var gpc by B("gpc", true)
    /** 0 = fingerprintingProtection (FPP), 1 = resistFingerprinting (RFP). RFP also spoofs en-US and a light color scheme. */
    var fpMode by I("fp_mode", 1)
    var webrtcMode by I("webrtc", 1) // 0 disabled, 1 no IP leak (default address only, no host candidates), 2 allowed
    var referrerTrim by B("referrer_trim", true)
    var jitOff by B("jit_off", false)
    var drm by B("drm", false)
    var blockPopups by B("block_popups", true)
    var stripParams by B("strip_params", true)

    // --- Security (payment-safe mode, phishing)
    var phishing by B("phishing", true)
    /** Domains the user confirmed via «Похоже на оплату» (always payment-safe). */
    var paymentUserDomains by SS("payment_user_domains", emptySet())
    /** Union of every payment-domains.json ever accepted from a signed pack (add-only). */
    var paymentKnown by SS("payment_known", emptySet())

    // --- Extensions
    /** JSON: add-on id → {"v": version, "sha": xpi sha256, "src": "bundled|amo|file"} recorded at install time. */
    var addonPins by S("addon_pins", "{}")
    /** Add-on ids the user marked «доверять на оплате» (pinned to id + version + sha256 at that moment): JSON id → "v|sha". */
    var addonPaymentTrust by S("addon_payment_trust", "{}")
    /** Add-ons the app paused (EnableSource.APP) while a «Банк» tab was open. */
    var addonsPaused by SS("addons_paused", emptySet())
    var uboInstalled by B("ubo_installed", false)

    // --- Updates (signed content packs + APK)
    var contentAuto by B("content_auto", true)
    var contentWifiOnly by B("content_wifi", true)
    var contentStatus by S("content_status", "")
    var contentLastCheck by S("content_last_check", "")
    var appUpdateCheck by B("app_update_check", true)
    var appUpdateStatus by S("app_update_status", "")
    var appLastCheck by S("app_last_check", "")
    /** Epoch ms of the last successful (verified) update.json check. */
    var appLastOk by S("app_last_ok", "0")
    var updateBannerDismissed by S("update_banner_dismissed", "")
    var contentHighWater: Long
        get() = sp.getLong("content_high_water", 0L)
        set(v) { sp.edit().putLong("content_high_water", v).apply() }

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
        val SEARCH_ENGINES = listOf(
            "ddg" to "DuckDuckGo", "brave" to "Brave Search", "startpage" to "Startpage",
            "mojeek" to "Mojeek", "yandex" to "Яндекс", "google" to "Google",
        )
    }
}
