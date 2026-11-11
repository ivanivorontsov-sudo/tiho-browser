package org.ivanivorontsov.tiho.shield

import org.junit.Assert.*
import org.junit.Test

class DomainMatcherTest {
    @Test fun suffixMatchAndParse() {
        val m = DomainMatcher()
        m.add("doubleclick.net", "google-ads")
        m.add("unityads.unity3d.com", "sdk-ads")
        assertEquals("google-ads", m.match("ad.doubleclick.net")?.list)
        assertEquals("doubleclick.net", m.match("ad.doubleclick.net")?.rule)
        assertEquals("sdk-ads", m.match("unityads.unity3d.com")?.list)
        assertNull(m.match("example.com"))
        assertNull(m.match("net"))
        val parsed = DomainMatcher.parseDomains("""
            # comment
            ||ads.example.com^
            0.0.0.0 tracker.test
            applovin.com
            1.2.3.4
            @@||good.example.com^
        """.trimIndent())
        assertTrue("ads.example.com" in parsed)
        assertTrue("tracker.test" in parsed)
        assertTrue("applovin.com" in parsed)
        assertFalse("1.2.3.4" in parsed)
        assertFalse("good.example.com" in parsed)
    }

    @Test fun listDefaults() {
        val d = ShieldLists.parseEnabled("")
        assertEquals(true, d[ShieldLists.GOOGLE])
        assertEquals(false, d[ShieldLists.TRACKING])
        val off = ShieldLists.parseEnabled("-")
        assertTrue(off.values.all { !it })
        assertEquals("google-ads,sdk-ads", ShieldLists.serializeEnabled(mapOf(ShieldLists.GOOGLE to true, ShieldLists.SDK to true, ShieldLists.TRACKING to false)))
    }
}
