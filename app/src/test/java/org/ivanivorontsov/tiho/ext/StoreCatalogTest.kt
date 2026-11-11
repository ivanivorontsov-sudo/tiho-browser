package org.ivanivorontsov.tiho.ext

import org.junit.Assert.*
import org.junit.Test

class StoreCatalogTest {
    @Test fun parsesStoreUrlsAndSearchJson() {
        val c = StoreCatalog.chromeIdFromUrl("https://chromewebstore.google.com/detail/ublock-origin/cjpalhdlnbpafiamejdnhcphjbkeiagm")
        assertEquals("chrome", c!!.store)
        assertEquals("cjpalhdlnbpafiamejdnhcphjbkeiagm", c.id)
        assertTrue(StoreCatalog.crxUrl(c).startsWith("https://clients2.google.com/"))
        val e = StoreCatalog.chromeIdFromUrl("https://microsoftedge.microsoft.com/addons/detail/ublock-origin/odfafepnkmbhccpbejgmiehpchacaeak")
        assertEquals("edge", e!!.store)
        assertTrue(StoreCatalog.crxUrl(e).contains("edge.microsoft.com"))
        assertTrue(StoreCatalog.isUserCssUrl("https://userstyles.world/api/style/1.user.css?x=1"))
        assertTrue(StoreCatalog.isCrxOrXpiUrl("https://example.com/a.crx"))

        val gf = StoreCatalog.parseGreasyFork("""[{"name":"Dark","description":"d","url":"https://greasyfork.org/scripts/1","code_url":"https://update.greasyfork.org/scripts/1/dark.user.js","users":9,"version":"1"}]""")
        assertEquals(1, gf.size)
        assertEquals("https://update.greasyfork.org/scripts/1/dark.user.js", gf[0].codeUrl)

        val us = StoreCatalog.parseUserstylesWorld("""{"data":[{"id":42,"name":"Wide","description":"w"}]}""")
        assertEquals(1, us.size)
        assertEquals("https://userstyles.world/api/style/42.user.css", us[0].codeUrl)
    }
}
