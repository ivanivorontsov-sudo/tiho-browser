package org.ivanivorontsov.tiho.gecko

import android.util.Log
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.Prefs
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.security.PaymentDomains
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import java.io.File

/**
 * The single GeckoRuntime. Static hardening prefs go into the YAML config (read once at start), everything
 * settings- or payment-list-dependent is pushed live through GeckoPreferenceController.
 */
class Engine(private val app: TihoApp) {
    lateinit var runtime: GeckoRuntime
        private set
    private val prefs: Prefs get() = app.prefs
    /** Exact payment hosts learned at runtime (Gecko's quarantine / restricted sets match exact host names). */
    private val learnedHosts: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    @Volatile var lastApplied: Map<String, Any> = emptyMap()
        private set

    fun start() {
        learnedHosts += prefs.sp.getStringSet("payment_hosts_learned", emptySet()).orEmpty()
        val cfg = File(app.filesDir, "gecko-config.yaml")
        // Android emulators without GPU acceleration: the GPU process times out and the compositor stalls; render in-process.
        val emu = android.os.Build.HARDWARE.let { it == "ranchu" || it == "goldfish" }
        val extra: Map<String, Any> = if (emu) mapOf("layers.gpu-process.enabled" to false, "gfx.webrender.software" to true) else emptyMap()
        cfg.writeText(GeckoPrefs.yaml(GeckoPrefs.static() + GeckoPrefs.dynamic(config()) + extra))
        val b = GeckoRuntimeSettings.Builder()
            .configFilePath(cfg.absolutePath)
            .locales(arrayOf("ru-RU", "ru", "en-US"))
            .contentBlocking(contentBlocking())
            .globalPrivacyControlEnabled(prefs.gpc)
            .allowInsecureConnections(if (prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
            .remoteDebuggingEnabled(false)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(false)
            .loginAutofillEnabled(false)
            .extensionsWebAPIEnabled(false)
            .preferredColorScheme(when (prefs.theme) { 1 -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT; 2 -> GeckoRuntimeSettings.COLOR_SCHEME_DARK; else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM })
        runtime = GeckoRuntime.create(app, b.build())
        // The YAML config covers start-up; the same prefs are also set through the pref API (belt and braces).
        GeckoPreferenceController.setGeckoPrefs(toSetList(GeckoPrefs.static())).accept({ r ->
            val failed = r?.filterValues { !it }?.keys.orEmpty()
            if (failed.isNotEmpty()) Log.w("Tiho", "static prefs not applied: $failed")
        }, { e -> Log.e("Tiho", "static prefs", e) })
        applySettings()
    }

    private fun contentBlocking(): ContentBlocking.Settings {
        val strict = prefs.etpStrict
        return ContentBlocking.Settings.Builder()
            .antiTracking(if (strict) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT)
            .enhancedTrackingProtectionLevel(if (strict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT)
            .strictSocialTrackingProtection(strict)
            // Total Cookie Protection (dFPI): third-party storage is partitioned per top-level site.
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookiePurging(true)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .queryParameterStrippingEnabled(prefs.stripParams)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .bounceTrackingProtectionMode(ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED)
            .emailTrackerBlockingPrivateMode(true)
            .build()
    }

    /** Re-applies runtime settings + dynamic prefs after a settings change. */
    fun applySettings() {
        val s = runtime.settings
        s.setGlobalPrivacyControl(prefs.gpc)
        s.setAllowInsecureConnections(if (prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
        s.setFingerprintingProtection(true)
        s.setFingerprintingProtectionPrivateBrowsing(true)
        s.setLnaEnabled(true)
        s.setLnaBlocking(true)
        s.setPostQuantumKeyExchangeEnabled(true)
        s.setLoginAutofillEnabled(false)
        s.setPreferredColorScheme(when (prefs.theme) { 1 -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT; 2 -> GeckoRuntimeSettings.COLOR_SCHEME_DARK; else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM })
        val doh = Prefs.dohUri(prefs.doh)
        if (doh != null) {
            s.setTrustedRecursiveResolverUri(doh)
            s.setTrustedRecursiveResolverMode(if (prefs.dohStrict) GeckoRuntimeSettings.TRR_MODE_ONLY else GeckoRuntimeSettings.TRR_MODE_FIRST)
        } else s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        val cb = s.contentBlocking
        val strict = prefs.etpStrict
        cb.setAntiTracking(if (strict) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT)
        cb.setEnhancedTrackingProtectionLevel(if (strict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT)
        cb.setStrictSocialTrackingProtection(strict)
        cb.setQueryParameterStrippingEnabled(prefs.stripParams)
        applyDynamicPrefs()
    }

    private fun toSetList(m: Map<String, Any>): List<SetGeckoPreference<*>> = m.map { (k, v) ->
        when (v) {
            is Boolean -> SetGeckoPreference.setBoolPref(k, v, GeckoPreferenceController.PREF_BRANCH_USER)
            is Int -> SetGeckoPreference.setIntPref(k, v, GeckoPreferenceController.PREF_BRANCH_USER)
            else -> SetGeckoPreference.setStringPref(k, v.toString(), GeckoPreferenceController.PREF_BRANCH_USER)
        }
    }

    /** Reads a live Gecko pref (diagnostics screen / tests). */
    fun readPref(name: String, cb: (String) -> Unit) {
        GeckoPreferenceController.getGeckoPref(name).accept({ p -> cb(p?.toString() ?: "—") }, { e -> cb("ошибка: ${e?.message}") })
    }

    fun paymentBaseDomains(): Set<String> = app.payments.matcher.domains

    fun paymentHosts(): Set<String> = GeckoPrefs.seedHosts(paymentBaseDomains()) + synchronized(learnedHosts) { learnedHosts.toSet() }

    fun config() = GeckoPrefs.Config(
        webrtcMode = prefs.webrtcMode, fpMode = prefs.fpMode, referrerTrim = prefs.referrerTrim, jitOff = prefs.jitOff,
        drm = prefs.drm, blockPopups = prefs.blockPopups,
        paymentHosts = paymentHosts(), paymentDomains = paymentBaseDomains(),
        trustedAddons = if (this::runtimeAddons.isInitialized) runtimeAddons().trustedForPayment() else emptySet(),
        allAddons = if (this::runtimeAddons.isInitialized) runtimeAddons().installedIds() else emptySet(),
    )

    lateinit var runtimeAddons: () -> Addons

    private var inFlight = 0
    private val waiters = ArrayList<() -> Unit>()

    /** Runs [cb] (main thread) once every pref change requested so far has been applied by Gecko. */
    fun afterPrefsApplied(cb: () -> Unit) { if (inFlight == 0) runSafely(cb) else waiters += cb }

    private fun runSafely(cb: () -> Unit) { try { cb() } catch (e: Throwable) { Log.e("Tiho", "afterPrefsApplied callback", e) } }

    private fun settled() {
        inFlight = maxOf(0, inFlight - 1)
        if (inFlight == 0) { val w = waiters.toList(); waiters.clear(); w.forEach { runSafely(it) } }
    }

    fun applyDynamicPrefs() {
        if (!this::runtime.isInitialized) return
        val m = GeckoPrefs.dynamic(config())
        val changed = m.filter { (k, v) -> lastApplied[k] != v }
        if (changed.isEmpty()) return
        lastApplied = m
        inFlight++
        GeckoPreferenceController.setGeckoPrefs(toSetList(changed)).accept({ r ->
            val failed = r?.filterValues { !it }?.keys.orEmpty()
            if (failed.isNotEmpty()) Log.w("Tiho", "gecko prefs not applied: $failed")
            settled()
            Log.i("Tiho", "gecko prefs applied: ${changed.size} changed, ${m["extensions.quarantinedDomains.list"].toString().count { it == ',' } + 1} payment hosts")
        }, { e -> Log.e("Tiho", "setGeckoPrefs failed", e); lastApplied = emptyMap(); settled() })
    }

    /**
     * Adds an exact payment host (seen in a «Банк» tab, or matching a payment base domain) to Gecko's quarantine /
     * restricted / exemption sets before the load proceeds. Returns true if the set changed.
     */
    fun learnPaymentHost(host: String?): Boolean {
        val h = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        if (!GeckoPrefs.validHost(h) || h in paymentHosts()) return false
        val isPay = app.payments.isPayment(h) || PaymentDomains.looksLikeAcs(h)
        if (!isPay) return false
        learnedHosts += h
        prefs.sp.edit().putStringSet("payment_hosts_learned", synchronized(learnedHosts) { learnedHosts.toSet() }).apply()
        applyDynamicPrefs()
        return true
    }

    fun clearAllData(done: () -> Unit = {}) {
        runtime.storageController.clearData(CLEAR_FLAGS).accept({ done() }, { done() })
    }

    fun clearContext(contextId: String) = runtime.storageController.clearDataForSessionContext(contextId)

    companion object {
        const val BANK_CONTEXT = "tiho-bank"
        /** Cookies, caches, DOM storage, permissions, site settings, auth sessions. */
        const val CLEAR_FLAGS = StorageController.ClearFlags.SITE_DATA or StorageController.ClearFlags.ALL_CACHES or StorageController.ClearFlags.AUTH_SESSIONS
    }
}
