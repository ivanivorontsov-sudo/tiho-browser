package org.ivanivorontsov.tiho.gecko

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gecko preference profile (Arkenfox / IronFox-style), pure JVM so it is unit-tested.
 *
 * Two layers:
 *  - [static] goes into the GeckoView YAML config file (read once at runtime start, "prefs:" section);
 *  - [dynamic] is (re)applied at runtime through GeckoPreferenceController whenever settings or the payment
 *    host set change (quarantine / restricted domains / fingerprinting exemptions are observed live by Gecko).
 */
object GeckoPrefs {
    data class Config(
        val webrtcMode: Int = 1,        // 0 off, 1 no IP leak, 2 allowed
        val fpMode: Int = 0,            // 0 FPP, 1 RFP
        val referrerTrim: Boolean = true,
        val jitOff: Boolean = false,
        val drm: Boolean = false,
        val blockPopups: Boolean = true,
        /** Exact host names treated as payment hosts (Gecko's domain sets are exact-host AtomSets). */
        val paymentHosts: Set<String> = emptySet(),
        /** Registrable payment domains (fingerprinting exemptions match the first-party site, i.e. eTLD+1). */
        val paymentDomains: Set<String> = emptySet(),
        /** Add-on ids whose payment-trust pin is currently valid (id + version + xpi sha256 match). */
        val trustedAddons: Set<String> = emptySet(),
        /** Every installed add-on id, so stale quarantineIgnoredByUser prefs can be reset. */
        val allAddons: Set<String> = emptySet(),
    )

    /** Mozilla's default restricted domains (extensions never run there). We append payment hosts when nothing is trusted. */
    const val DEFAULT_RESTRICTED = "accounts-static.cdn.mozilla.net,accounts.firefox.com,addons.cdn.mozilla.net,addons.mozilla.org," +
        "api.accounts.firefox.com,content.cdn.mozilla.net,discovery.addons.mozilla.org,install.mozilla.org,oauth.accounts.firefox.com," +
        "profile.accounts.firefox.com,support.mozilla.org,sync.services.mozilla.com"

    /** Telemetry / studies / crash reports / prefetch / speculative connections: fixed, written to the YAML config. */
    fun static(): Map<String, Any> = linkedMapOf(
        // Telemetry & data reporting
        "toolkit.telemetry.enabled" to false,
        "toolkit.telemetry.unified" to false,
        "toolkit.telemetry.archive.enabled" to false,
        "toolkit.telemetry.geckoview.streaming" to false,
        "toolkit.telemetry.server" to "data:,",
        "toolkit.telemetry.newProfilePing.enabled" to false,
        "toolkit.telemetry.shutdownPingSender.enabled" to false,
        "toolkit.telemetry.firstShutdownPing.enabled" to false,
        "toolkit.telemetry.bhrPing.enabled" to false,
        "toolkit.coverage.opt-out" to true,
        "toolkit.coverage.endpoint.base" to "",
        "datareporting.healthreport.uploadEnabled" to false,
        "datareporting.policy.dataSubmissionEnabled" to false,
        "datareporting.usage.uploadEnabled" to false,
        "browser.ping-centre.telemetry" to false,
        "dom.security.unexpected_system_load_telemetry_enabled" to false,
        "network.trr.confirmation_telemetry_enabled" to false,
        // Studies / Normandy / experiments
        "app.normandy.enabled" to false,
        "app.normandy.api_url" to "",
        "app.shield.optoutstudies.enabled" to false,
        "nimbus.enabled" to false,
        // Crash reporting (GeckoView: no crash handler is registered either)
        "breakpad.reportURL" to "",
        "browser.tabs.crashReporting.sendReport" to false,
        "browser.crashReports.unsubmittedCheck.autoSubmit2" to false,
        // Captive portal / connectivity probes
        "network.captive-portal-service.enabled" to false,
        "network.connectivity-service.enabled" to false,
        // Safe Browsing stays on (privacy-preserving hash prefixes), but never send download metadata to Google
        "browser.safebrowsing.downloads.remote.enabled" to false,
        "browser.safebrowsing.downloads.remote.url" to "",
        // Prefetch & speculative connections
        "network.prefetch-next" to false,
        "network.dns.disablePrefetch" to true,
        "network.dns.disablePrefetchFromHTTPS" to true,
        "network.predictor.enabled" to false,
        "network.predictor.enable-prefetch" to false,
        "network.http.speculative-parallel-limit" to 0,
        "browser.places.speculativeConnect.enabled" to false,
        "browser.urlbar.speculativeConnect.enabled" to false,
        "network.early-hints.preconnect.enabled" to false,
        // Misc hardening
        "network.IDN_show_punycode" to true,
        "security.ssl.require_safe_negotiation" to true,
        "security.tls.enable_0rtt_data" to false,
        "security.OCSP.require" to false,
        "security.pki.crlite_mode" to 2,
        "security.cert_pinning.enforcement_level" to 2,
        "security.mixed_content.block_display_content" to true,
        "dom.security.https_only_mode_send_http_background_request" to false,
        "privacy.partition.network_state" to true,
        "privacy.partition.serviceWorkers" to true,
        "privacy.query_stripping.enabled" to true,
        "privacy.query_stripping.enabled.pbmode" to true,
        // Remote settings are off, so give Gecko's query stripping our own list (utm_* prefixes are handled by uBO filters).
        "privacy.query_stripping.strip_list" to org.ivanivorontsov.tiho.privacy.UrlCleaner.GLOBAL.sorted().joinToString(" "),
        "privacy.bounceTrackingProtection.mode" to 1,
        "network.http.referer.spoofSource" to false,
        "dom.battery.enabled" to false,
        "device.sensors.motion.enabled" to false,
        "beacon.enabled" to false,
        "dom.push.enabled" to false,
        "dom.webnotifications.enabled" to false,
        "geo.provider.network.url" to "",
        "browser.send_pings" to false,
        "network.http.http3.enable_0rtt" to false,
        "extensions.webextensions.remote" to true,
        // GeckoView ships play-services-fido for WebAuthn; we exclude Google Play Services, so WebAuthn is off.
        "security.webauth.webauthn" to false,
        // Extension updates are triggered explicitly by the app (pins!), never silently.
        "extensions.update.enabled" to false,
        "extensions.update.autoUpdateDefault" to false,
        "extensions.getAddons.cache.enabled" to false,
    )

    /** Settings-dependent prefs, applied live. */
    fun dynamic(c: Config): Map<String, Any> {
        val m = linkedMapOf<String, Any>()
        // WebRTC IP leak protection
        m["media.peerconnection.enabled"] = c.webrtcMode != 0
        m["media.peerconnection.ice.default_address_only"] = c.webrtcMode == 1
        m["media.peerconnection.ice.no_host"] = c.webrtcMode == 1
        m["media.peerconnection.ice.proxy_only_if_behind_proxy"] = c.webrtcMode == 1
        m["media.peerconnection.ice.relay_only"] = false
        // Referrer: cross-origin → scheme+host+port only
        m["network.http.referer.XOriginTrimmingPolicy"] = if (c.referrerTrim) 2 else 0
        m["network.http.referer.XOriginPolicy"] = 0
        // DRM
        m["media.eme.enabled"] = c.drm
        m["media.gmp-widevinecdm.enabled"] = c.drm
        // Popups
        m["dom.disable_open_during_load"] = c.blockPopups
        // JIT (global; Gecko has no per-origin JIT switch). JavaScript itself stays enabled.
        val jit = !c.jitOff
        m["javascript.options.baselinejit"] = jit
        m["javascript.options.ion"] = jit
        m["javascript.options.native_regexp"] = jit
        m["javascript.options.wasm_baselinejit"] = jit
        m["javascript.options.wasm_optimizingjit"] = jit
        m["javascript.options.asmjs"] = jit
        // Fingerprinting: FPP (Firefox strict-mode style) or RFP (Tor-like), payment hosts relaxed in both modes.
        val hosts = c.paymentHosts.filter { validHost(it) }.sorted()
        m["privacy.resistFingerprinting"] = c.fpMode == 1
        m["privacy.resistFingerprinting.pbmode"] = c.fpMode == 1
        val bases = c.paymentDomains.filter { validHost(it) }.sorted()
        m["privacy.resistFingerprinting.exemptedDomains"] = bases.flatMap { listOf(it, "*.$it") }.joinToString(",")
        m["privacy.fingerprintingProtection"] = true
        m["privacy.fingerprintingProtection.pbmode"] = true
        m["privacy.fingerprintingProtection.granularOverrides"] = granularOverrides(bases)
        // Payment-safe mode: quarantine for all payment hosts; restricted (no extension at all, even Mozilla-recommended)
        // when no add-on is trusted for payments.
        m["extensions.quarantinedDomains.enabled"] = true
        m["extensions.quarantinedDomains.list"] = hosts.joinToString(",")
        m["extensions.webextensions.restrictedDomains"] =
            if (c.trustedAddons.isEmpty() && hosts.isNotEmpty()) DEFAULT_RESTRICTED + "," + hosts.joinToString(",") else DEFAULT_RESTRICTED
        for (id in c.allAddons + c.trustedAddons) m["extensions.quarantineIgnoredByUser.$id"] = id in c.trustedAddons
        return m
    }

    /** privacy.fingerprintingProtection.granularOverrides: disable every FPP target as first party on payment hosts. */
    fun granularOverrides(sites: List<String>): String {
        val a = JSONArray()
        for (h in sites) a.put(JSONObject().put("firstPartyDomain", h).put("overrides", "-AllTargets"))
        return a.toString()
    }

    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    fun validHost(h: String) = h.length <= 253 && HOST_RE.matches(h)

    /** Subdomain prefixes pre-seeded for every payment base domain (Gecko matches exact hosts; others are learned at runtime). */
    val SEED_PREFIXES = listOf("", "www.", "online.", "checkout.", "pay.", "payment.", "payments.", "secure.", "3ds.", "acs.", "id.",
        "login.", "auth.", "m.", "js.", "api.", "m.stripe.", "hooks.", "web.", "lk.", "my.", "ib.", "i.", "e.", "business.")

    fun seedHosts(baseDomains: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (d in baseDomains) for (p in SEED_PREFIXES) { val h = p + d; if (validHost(h)) out += h }
        return out
    }

    /** YAML for GeckoRuntimeSettings.configFilePath ("prefs:" map). Strings are JSON-quoted (valid YAML). */
    fun yaml(prefs: Map<String, Any>, args: List<String> = emptyList()): String = buildString {
        if (args.isNotEmpty()) { append("args:\n"); args.forEach { append("  - ").append(JSONObject.quote(it)).append('\n') } }
        append("prefs:\n")
        for ((k, v) in prefs) {
            append("  ").append(JSONObject.quote(k)).append(": ")
            append(when (v) { is String -> JSONObject.quote(v); else -> v.toString() }).append('\n')
        }
    }
}
