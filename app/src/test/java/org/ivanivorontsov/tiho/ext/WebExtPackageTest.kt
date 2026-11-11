package org.ivanivorontsov.tiho.ext

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebExtPackageTest {
    private fun zip(files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun crx3(zip: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("Cr24".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        out.write(zip)
        return out.toByteArray()
    }

    @Test fun extractsContentScriptsFromCrxAndXpi() {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "Demo Ext",
              "version": "4.0",
              "description": "hello",
              "background": { "service_worker": "bg.js" },
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["a.js"],
                "css": ["a.css"],
                "run_at": "document_end"
              }]
            }
        """.trimIndent()
        val files = mapOf(
            "manifest.json" to manifest,
            "a.js" to "console.log('hi');",
            "a.css" to "body{outline:1px solid red}",
            "bg.js" to "chrome.runtime.onInstalled.addListener(()=>{});",
        )
        val preview = WebExtPackage.parse(crx3(zip(files)))
        assertEquals("Demo Ext", preview.name)
        assertEquals("4.0", preview.version)
        assertEquals(1, preview.scripts.size)
        assertEquals(1, preview.styles.size)
        assertTrue(preview.scripts[0].js.contains("console.log"))
        assertTrue(preview.unsupported.any { it.contains("service worker") })
        val scripts = WebExtPackage.toUserScripts(preview, "https://example.com/x.crx")
        assertEquals(1, scripts.size)
        assertTrue(scripts[0].appliesTo("https://example.com/page"))
        val styles = WebExtPackage.toUserStyles(preview, "https://example.com/x.crx")
        assertEquals(1, styles.size)
        assertTrue(styles[0].css.contains("outline"))

        val xpi = WebExtPackage.parse(zip(files))
        assertEquals("Demo Ext", xpi.name)
    }

    @Test fun rejectsNonZip() {
        try {
            WebExtPackage.parse("not a package".toByteArray())
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("не CRX") || e.message!!.contains("короткий") || e.message!!.isNotEmpty())
        }
    }
}
