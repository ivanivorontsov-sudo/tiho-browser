package org.ivanivorontsov.tiho.ext

import org.junit.Assert.*
import org.junit.Test

class UserStyleTest {
    @Test fun parsesMozDocumentAndMetadata() {
        val src = """
            /* ==UserStyle==
            @name Dark Example
            @namespace test
            @version 1.2
            @description demo
            ==/UserStyle== */
            @-moz-document domain("example.com"), url-prefix("https://news.example.com/") {
              body { background: black !important; }
            }
        """.trimIndent()
        val s = UserStyle.parse(src, "https://userstyles.world/api/style/1.user.css")
        assertEquals("Dark Example", s.name)
        assertEquals("1.2", s.version)
        assertTrue(s.appliesTo("https://example.com/page"))
        assertTrue(s.appliesTo("https://www.example.com/"))
        assertTrue(s.appliesTo("https://news.example.com/today"))
        assertFalse(s.appliesTo("https://other.test/"))
        assertTrue(s.css.contains("background: black"))
    }

    @Test fun matchMetadataWithoutMozDocument() {
        val src = """
            /* ==UserStyle==
            @name Wide
            @match https://*.wikipedia.org/*
            ==/UserStyle== */
            #content { max-width: none; }
        """.trimIndent()
        val s = UserStyle.parse(src)
        assertTrue(s.appliesTo("https://ru.wikipedia.org/wiki/X"))
        assertFalse(s.appliesTo("https://example.com/"))
    }
}
