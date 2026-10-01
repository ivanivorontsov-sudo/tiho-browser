package org.ivanivorontsov.tiho.security

import org.junit.Assert.*
import org.junit.Test

class ThreatListTest {
    private val t = ThreatList.parse(
        """
        ! phishing-filter (AdGuard)
        ||evil-login.example^${'$'}all
        ||sites.example/phish/login^${'$'}all
        @@||good.example^
        example.com##.ad
        """.trimIndent(),
        "0.0.0.0 malware.test\n# comment\nbare-domain.test\n||urlhaus.test/bin/x.sh^\n||*.wild.test^",
    )

    @Test fun hostMatches() {
        assertNotNull(t.match("https://evil-login.example/"))
        assertNotNull(t.match("https://sub.evil-login.example/a?b"))
        assertNotNull(t.match("http://malware.test/x"))
        assertNotNull(t.match("https://bare-domain.test"))
        assertNull(t.match("https://notevil-login.example/"))
        assertNull(t.match("https://good.example/"))
    }

    @Test fun pathMatches() {
        assertEquals("sites.example/phish/login", t.match("https://sites.example/phish/login?u=1")?.rule)
        assertNotNull(t.match("https://sites.example/phish/login/step2"))
        assertNull(t.match("https://sites.example/phish/loginx"))
        assertNull(t.match("https://sites.example/other"))
        assertNotNull(t.match("http://urlhaus.test/bin/x.sh"))
    }

    @Test fun ignoresNonHttpAndJunk() {
        assertNull(t.match("about:blank"))
        assertNull(t.match("data:text/html,x"))
        assertNull(t.match("https://x.wild.test/"))
        assertEquals(0, ThreatList.EMPTY.size)
        assertTrue(t.size >= 5)
    }

    @Test fun bundledListsParse() {
        val dir = System.getProperty("tiho.assets") ?: return
        val f = java.io.File(dir, "security/phishing-filter.txt")
        if (!f.exists()) return
        val big = ThreatList.parse(f.readText(), java.io.File(dir, "security/urlhaus.txt").readText())
        assertTrue("bundled lists have rules", big.size > 10_000)
        assertNull(big.match("https://www.google.com/"))
        assertNull(big.match("https://online.sberbank.ru/"))
    }
}
