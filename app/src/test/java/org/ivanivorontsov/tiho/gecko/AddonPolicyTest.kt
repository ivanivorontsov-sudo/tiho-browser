package org.ivanivorontsov.tiho.gecko

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AddonPolicyTest {
    private val sha = "5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287"

    @Test fun pinsRoundTrip() {
        val p = mapOf("u@x" to AddonPolicy.Pin("1.75.0", sha, "bundled"))
        assertEquals(p, AddonPolicy.parsePins(AddonPolicy.pinsJson(p)))
        assertTrue(AddonPolicy.parsePins("garbage").isEmpty())
    }

    @Test fun trustIsPinnedToVersionAndHash() {
        val pins = mapOf("u@x" to AddonPolicy.Pin("1.75.0", sha, "amo"), "n@x" to AddonPolicy.Pin("2.0", "unknown", "amo"))
        val trust = mapOf("u@x" to "1.75.0|$sha", "n@x" to "2.0|unknown")
        assertEquals(setOf("u@x"), AddonPolicy.validTrusted(trust, pins, mapOf("u@x" to "1.75.0", "n@x" to "2.0")))
        // updated add-on (new version) → trust no longer valid
        assertEquals(emptySet<String>(), AddonPolicy.validTrusted(trust, pins, mapOf("u@x" to "1.76.0")))
        assertEquals(setOf("u@x"), AddonPolicy.brokenTrust(trust, pins, mapOf("u@x" to "1.76.0")))
        // re-pinned with a different hash → old trust token rejected
        val pins2 = mapOf("u@x" to AddonPolicy.Pin("1.75.0", "0".repeat(64), "amo"))
        assertTrue(AddonPolicy.validTrusted(trust, pins2, mapOf("u@x" to "1.75.0")).isEmpty())
        assertFalse(AddonPolicy.trustable("n@x", "2.0", pins))
    }

    @Test fun parseAmoAddon() {
        val j = JSONObject("""{"guid":"uBlock0@raymondhill.net","slug":"ublock-origin","name":{"ru":"uBlock Origin"},"summary":{"en-US":"Finally"},
            "average_daily_users":10,"promoted":[{"category":"recommended","apps":["android"]}],"icon_url":"https://addons.mozilla.org/i.png",
            "current_version":{"version":"1.75.0","file":{"url":"https://addons.mozilla.org/android/downloads/file/5034826/ublock_origin-1.75.0.xpi","hash":"sha256:$sha","size":4000000}}}""")
        val a = AddonPolicy.parseAddon(j)
        assertEquals("uBlock Origin", a.name); assertEquals("Finally", a.summary); assertTrue(a.recommended); assertEquals(sha, a.sha256)
        for (bad in listOf(j.toString().replace("https://addons.mozilla.org/android/downloads", "https://evil.example/d"),
                j.toString().replace("sha256:$sha", "md5:abc"), j.toString().replace("https://addons.mozilla.org/android/downloads", "http://addons.mozilla.org/android/downloads"))) {
            assertThrows(IllegalArgumentException::class.java) { AddonPolicy.parseAddon(JSONObject(bad)) }
        }
        assertEquals(1, AddonPolicy.parseSearch("""{"results":[$j, {"guid":"broken"}]}""").size)
    }

    @Test fun amoUrls() {
        assertEquals("darkreader", AddonPolicy.slugFromListing("https://addons.mozilla.org/ru/android/addon/darkreader/"))
        assertEquals("ublock-origin", AddonPolicy.slugFromListing("https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/reviews/"))
        assertNull(AddonPolicy.slugFromListing("https://evil.example/addon/x/"))
        assertTrue(AddonPolicy.isXpiUrl("https://addons.mozilla.org/android/downloads/file/1/x-1.0.xpi"))
        assertFalse(AddonPolicy.isXpiUrl("https://evil.example/x.xpi"))
        assertFalse(AddonPolicy.isXpiUrl("http://addons.mozilla.org/x.xpi"))
        assertTrue(AddonPolicy.searchUrl("тёмная тема").contains("q=%D1%82"))
    }
}
