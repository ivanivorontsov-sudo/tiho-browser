package org.ivanivorontsov.tiho.ext

import org.junit.Assert.*
import org.junit.Test

class IsolationTest {
    @Test fun tokensBindSenders() {
        val a = ScriptTokens.tokenFor("a"); val b = ScriptTokens.tokenFor("b")
        assertNotEquals(a, b); assertEquals(32, a.length); assertEquals(a, ScriptTokens.tokenFor("a"))
        assertTrue(ScriptTokens.check("a", a)); assertFalse(ScriptTokens.check("a", b))
        assertFalse(ScriptTokens.check("a", null)); assertFalse(ScriptTokens.check("unknown", a))
    }
    @Test fun wrapperCarriesOwnTokenAndPrelude() {
        val s = UserScript.parse("// ==UserScript==\n// @name X\n// @match https://*/*\n// ==/UserScript==\nGM_setValue('k',1)")
        val w = UserScriptJs.wrapper(s, "const __tihoPay=false;", false)
        assertTrue(w.startsWith(UserScriptJs.PRELUDE))
        assertTrue(w.contains(ScriptTokens.tokenFor(s.id)))
        assertTrue(w.contains("m.tok=__tok"))
    }
    @Test fun redosPatternsNeverMatch() {
        assertEquals(UserScript.NEVER, UserScript.safeUserRegex("(a+)+$"))
        assertEquals(UserScript.NEVER, UserScript.safeUserRegex("(\\w*)*x"))
        assertEquals(UserScript.NEVER, UserScript.safeUserRegex("(a)\\1"))
        assertEquals(UserScript.NEVER, UserScript.safeUserRegex("a".repeat(301)))
        assertEquals(UserScript.NEVER, UserScript.safeUserRegex("[unclosed"))
        assertEquals("^https://example\\.com/", UserScript.safeUserRegex("^https://example\\.com/"))
        assertEquals(UserScript.NEVER, UserScript.globToRegex("/(a+)+/"))
    }
}
