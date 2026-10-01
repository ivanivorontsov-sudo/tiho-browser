package org.ivanivorontsov.tiho.ext

import org.ivanivorontsov.tiho.security.Payments
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

class UserScriptJsTest {
    private val src = """
// ==UserScript==
// @name Test
// @version 1.0
// @match https://example.com/*
// @grant GM_setValue
// ==/UserScript==
GM_setValue("k", GM_getValue("k", 0) + 1);
"""
    private val s = UserScript.parse(src).let { it.copy(sha256 = UserScriptPin.sha256(it.code)) }

    @Test fun nativeMatchesAndExcludes() {
        assertEquals(listOf("https://example.com/*"), UserScriptJs.nativeMatches(s))
        assertNull(UserScriptJs.nativeMatches(s.copy(includes = listOf("*example*"))))
        assertEquals(listOf("*://*.sberbank.ru/*", "*://*.stripe.com/*"), UserScriptJs.paymentExcludes(setOf("stripe.com", "sberbank.ru")))
        assertEquals("document_start", UserScriptJs.runAt("document-start"))
    }

    @Test fun configSkipsDisabledAndTampered() {
        val cfg = UserScriptJs.config(listOf(s, s.copy(id = "off", enabled = false), s.copy(id = "bad", code = "x()")), { emptyMap() }, setOf("stripe.com"), Payments.guardJs("[]"), true)
        val arr = cfg.getJSONArray("scripts")
        assertEquals(1, arr.length())
        assertFalse(arr.getJSONObject(0).getBoolean("trusted"))
        assertEquals("*://*.stripe.com/*", cfg.getJSONArray("paymentExclude").getString(0))
    }

    @Test fun wrapperIsValidJs() {
        val code = UserScriptJs.wrapper(s, Payments.guardJs("[\"stripe.com\"]"), false)
        assertTrue(code.contains("if(!false&&__tihoPay)return;"))
        assertTrue(code.contains("/*__TIHO_VALUES__*/{}"))
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").map(::File).firstOrNull { it.canExecute() }
        Assume.assumeTrue("node not available", node != null)
        val f = File.createTempFile("tiho-us", ".js"); f.writeText(code)
        val p = ProcessBuilder(node!!.path, "--check", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }
}
