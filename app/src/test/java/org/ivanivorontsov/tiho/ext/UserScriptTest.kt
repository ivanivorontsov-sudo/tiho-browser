package org.ivanivorontsov.tiho.ext

import org.junit.Assert.*
import org.junit.Test

class UserScriptTest {
    private val src = """
        // ==UserScript==
        // @name         Test script
        // @namespace    tiho.test
        // @version      1.2
        // @description  Does things
        // @match        https://*.example.com/*
        // @include      http*://news.test/article/*
        // @exclude      *://*.example.com/admin*
        // @run-at       document-start
        // @grant        GM_getValue
        // @grant        GM_setValue
        // @require      https://cdn.test/lib.js
        // @noframes
        // ==/UserScript==
        console.log('hi');
    """.trimIndent()

    @Test fun parsesMetadata() {
        val s = UserScript.parse(src)
        assertEquals("Test script", s.name)
        assertEquals("1.2", s.version)
        assertEquals("document-start", s.runAt)
        assertTrue(s.noframes)
        assertEquals(listOf("GM_getValue", "GM_setValue"), s.grants)
        assertEquals(listOf("https://cdn.test/lib.js"), s.requires)
        assertEquals(listOf("https://*.example.com/*"), s.matches)
    }

    @Test fun matchesUrls() {
        val s = UserScript.parse(src)
        assertTrue(s.appliesTo("https://www.example.com/page"))
        assertTrue(s.appliesTo("https://example.com/"))
        assertFalse(s.appliesTo("http://www.example.com/page"))
        assertFalse(s.appliesTo("https://www.example.com/admin/x"))
        assertFalse(s.appliesTo("https://evil.test/?https://www.example.com/"))
        assertTrue(s.appliesTo("https://news.test/article/42"))
        assertFalse(s.appliesTo("https://news.test/other"))
    }

    @Test fun allUrlsAndStableId() {
        val a = UserScript.parse("// ==UserScript==\n// @name A\n// @match <all_urls>\n// ==/UserScript==\n")
        assertTrue(a.appliesTo("https://any.test/x"))
        assertEquals(a.id, UserScript.parse("// ==UserScript==\n// @name A\n// @match <all_urls>\n// ==/UserScript==\nchanged()").id)
    }
}
