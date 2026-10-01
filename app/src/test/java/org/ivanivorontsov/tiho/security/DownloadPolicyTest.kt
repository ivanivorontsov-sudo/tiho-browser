package org.ivanivorontsov.tiho.security

import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun dangerousTypes() {
        assertTrue(DownloadPolicy.assess("update.apk", "https://x/update.apk", null).dangerous)
        assertTrue(DownloadPolicy.assess("invoice.pdf.exe", "https://x/", "application/pdf").dangerous)
        assertTrue(DownloadPolicy.assess("file", "https://x/", "application/vnd.android.package-archive").dangerous)
        assertTrue(DownloadPolicy.assess("run.SH", "https://x/", null).dangerous)
        assertTrue(DownloadPolicy.assess("page.html", "https://x/", "text/html").dangerous)
    }

    @Test fun safeTypes() {
        val r = DownloadPolicy.assess("photo.jpg", "https://x/photo.jpg", "image/jpeg")
        assertFalse(r.any)
        assertEquals("", DownloadPolicy.warningText(r))
        assertFalse(DownloadPolicy.assess("report.pdf", "https://x/", "application/pdf; charset=binary").dangerous)
    }

    @Test fun insecureTransport() {
        val r = DownloadPolicy.assess("doc.pdf", "http://x/doc.pdf", "application/pdf")
        assertTrue(r.insecure && !r.dangerous)
        assertTrue(DownloadPolicy.warningText(r).contains("HTTP"))
        assertTrue(DownloadPolicy.warningText(DownloadPolicy.assess("a.apk", "http://x/a.apk", null)).lines().size == 2)
    }
}
