package org.ivanivorontsov.tiho.gecko

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GeckoPrefsTest {
    private val hosts = GeckoPrefs.seedHosts(setOf("stripe.com", "sberbank.ru"))

    @Test fun staticHardening() {
        val s = GeckoPrefs.static()
        assertEquals(false, s["toolkit.telemetry.enabled"])
        assertEquals(false, s["datareporting.healthreport.uploadEnabled"])
        assertEquals(false, s["app.normandy.enabled"])
        assertEquals("", s["breakpad.reportURL"])
        assertEquals(false, s["network.prefetch-next"])
        assertEquals(true, s["network.dns.disablePrefetch"])
        assertEquals(0, s["network.http.speculative-parallel-limit"])
        assertEquals(false, s["network.captive-portal-service.enabled"])
        assertEquals(false, s["extensions.update.enabled"])
        assertEquals(false, s["security.webauth.webauthn"])
        assertEquals(false, s["privacy.donottrackheader.enabled"])
    }

    @Test fun webrtcModes() {
        val off = GeckoPrefs.dynamic(GeckoPrefs.Config(webrtcMode = 0))
        assertEquals(false, off["media.peerconnection.enabled"])
        val safe = GeckoPrefs.dynamic(GeckoPrefs.Config(webrtcMode = 1))
        assertEquals(true, safe["media.peerconnection.enabled"])
        assertEquals(true, safe["media.peerconnection.ice.default_address_only"])
        assertEquals(true, safe["media.peerconnection.ice.no_host"])
        val open = GeckoPrefs.dynamic(GeckoPrefs.Config(webrtcMode = 2))
        assertEquals(false, open["media.peerconnection.ice.no_host"])
    }

    @Test fun jitToggleKeepsJavascript() {
        val m = GeckoPrefs.dynamic(GeckoPrefs.Config(jitOff = true))
        assertEquals(false, m["javascript.options.ion"])
        assertEquals(false, m["javascript.options.baselinejit"])
        assertFalse(m.containsKey("javascript.enabled"))
        assertEquals(true, GeckoPrefs.dynamic(GeckoPrefs.Config())["javascript.options.ion"])
    }

    @Test fun fingerprintingExemptionsForPaymentSites() {
        val m = GeckoPrefs.dynamic(GeckoPrefs.Config(fpMode = 1, paymentHosts = hosts, paymentDomains = setOf("stripe.com", "sberbank.ru")))
        assertEquals(true, m["privacy.resistFingerprinting"])
        val ex = (m["privacy.resistFingerprinting.exemptedDomains"] as String).split(",")
        assertTrue("*.stripe.com" in ex && "stripe.com" in ex && "*.sberbank.ru" in ex)
        val ov = JSONArray(m["privacy.fingerprintingProtection.granularOverrides"] as String)
        assertEquals(2, ov.length())
        assertEquals("-AllTargets", ov.getJSONObject(0).getString("overrides"))
        assertEquals(false, GeckoPrefs.dynamic(GeckoPrefs.Config(fpMode = 0))["privacy.resistFingerprinting"])
        assertEquals(true, GeckoPrefs.dynamic(GeckoPrefs.Config(fpMode = 0))["privacy.fingerprintingProtection"])
    }

    @Test fun rfpMatchesOneCohort() {
        val r = GeckoPrefs.dynamic(GeckoPrefs.Config(fpMode = 1, theme = 2))
        assertEquals(2, r["privacy.spoof_english"])
        assertEquals("en-US, en", r["intl.accept_languages"])
        assertEquals(true, r["privacy.resistFingerprinting.letterboxing"])
        assertEquals(false, r["privacy.donottrackheader.enabled"])
        assertEquals(false, r["browser.display.use_system_colors"])
        assertEquals(1, r["layout.css.prefers-color-scheme.content-override"])
        val f = GeckoPrefs.dynamic(GeckoPrefs.Config(fpMode = 0, theme = 2))
        assertEquals(1, f["privacy.spoof_english"])
        assertEquals("ru-RU, ru, en-US, en", f["intl.accept_languages"])
        assertEquals(0, f["layout.css.prefers-color-scheme.content-override"])
        assertEquals(false, r["webgl.enable-webgl2"])
        assertEquals(false, r["dom.netinfo.enabled"])
        assertEquals(true, f["webgl.enable-webgl2"])
        assertEquals(true, f["dom.netinfo.enabled"])
    }

    @Test fun commonApisStayLikeTorBrowser() {
        val s = GeckoPrefs.static()
        assertEquals(true, s["beacon.enabled"])
        assertEquals(true, s["dom.webnotifications.enabled"])
        assertEquals(true, s["privacy.resistFingerprinting.randomDataOnCanvasExtract"])
        assertEquals(false, s["dom.push.enabled"])
        assertEquals(false, s["dom.battery.enabled"])
    }

    @Test fun letterboxRoundsHeightDown() {
        assertEquals(700f, org.ivanivorontsov.tiho.ui.letterboxHeight(742.5f, 50))
        assertEquals(650f, org.ivanivorontsov.tiho.ui.letterboxHeight(650f, 50))
        assertEquals(742.5f, org.ivanivorontsov.tiho.ui.letterboxHeight(742.5f, 0))
        assertEquals(80f, org.ivanivorontsov.tiho.ui.letterboxHeight(80f, 50))
    }

    @Test fun quarantineAndRestrictedDomains() {
        // nothing trusted → payment hosts are restricted for every extension (even Mozilla-recommended ones)
        val strict = GeckoPrefs.dynamic(GeckoPrefs.Config(paymentHosts = hosts, allAddons = setOf("a@x", "uBlock0@raymondhill.net")))
        val restricted = (strict["extensions.webextensions.restrictedDomains"] as String).split(",")
        assertTrue("addons.mozilla.org" in restricted && "checkout.stripe.com" in restricted && "online.sberbank.ru" in restricted)
        assertEquals(true, strict["extensions.quarantinedDomains.enabled"])
        assertTrue((strict["extensions.quarantinedDomains.list"] as String).split(",").contains("js.stripe.com"))
        assertEquals(false, strict["extensions.quarantineIgnoredByUser.a@x"])
        // a trusted (pinned) add-on → quarantine instead of restricted, only that id ignores it
        val t = GeckoPrefs.dynamic(GeckoPrefs.Config(paymentHosts = hosts, trustedAddons = setOf("uBlock0@raymondhill.net"), allAddons = setOf("a@x", "uBlock0@raymondhill.net")))
        assertEquals(GeckoPrefs.DEFAULT_RESTRICTED, t["extensions.webextensions.restrictedDomains"])
        assertEquals(true, t["extensions.quarantineIgnoredByUser.uBlock0@raymondhill.net"])
        assertEquals(false, t["extensions.quarantineIgnoredByUser.a@x"])
    }

    @Test fun hostValidation() {
        assertTrue(GeckoPrefs.validHost("www.sberbank.ru"))
        for (bad in listOf("a,b.com", "*.x.com", "x", "-a.com", "a b.com", "a..com")) assertFalse(bad, GeckoPrefs.validHost(bad))
        val m = GeckoPrefs.dynamic(GeckoPrefs.Config(paymentHosts = setOf("ok.com", "evil.com,addons.mozilla.org")))
        assertEquals("ok.com", m["extensions.quarantinedDomains.list"])
    }

    @Test fun yamlIsQuoted() {
        val y = GeckoPrefs.yaml(linkedMapOf("a.b" to true, "c" to 2, "d" to "x\"y: z"))
        assertEquals("prefs:\n  \"a.b\": true\n  \"c\": 2\n  \"d\": \"x\\\"y: z\"\n", y)
    }
}
