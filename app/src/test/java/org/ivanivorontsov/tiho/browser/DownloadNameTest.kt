package org.ivanivorontsov.tiho.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNameTest {
    @Test fun bidiOverridesStripped() {
        assertEquals("invoicefdp.apk", DownloadCtl.fileName("https://x.ru/a", "attachment; filename=\"invoice\u202Efdp.apk\"", null))
        assertEquals("a_b.txt", DownloadCtl.fileName("https://x.ru/a", "attachment; filename=\"a/b.txt\"", null))
    }
}
