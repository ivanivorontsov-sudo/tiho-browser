package org.ivanivorontsov.tiho.ext

import org.junit.Assert.*
import org.junit.Test

class UserScriptPinTest {
    private val src = """
        // ==UserScript==
        // @name  Pin test
        // @match https://example.com/*
        // @require https://cdn.example/lib.js
        // ==/UserScript==
        console.log(1)
    """.trimIndent()

    private fun pinned(code: String = src, lib: String = "var lib=1") =
        UserScriptPin.pin(UserScript.parse(code, "https://example.com/a.user.js"), mapOf("https://cdn.example/lib.js" to lib))

    @Test fun hashesArePinned() {
        val s = pinned()
        assertEquals(UserScriptPin.sha256(src), s.sha256)
        assertEquals(64, s.sha256.length)
        assertEquals(UserScriptPin.sha256("var lib=1"), s.requireHashes["https://cdn.example/lib.js"])
        assertTrue(s.requireCode.contains("var lib=1"))
        assertTrue(UserScriptPin.intact(s))
    }

    @Test fun knownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UserScriptPin.sha256(""))
        assertEquals("e3b0 c442", UserScriptPin.pretty("e3b0c442"))
    }

    @Test fun changeDetection() {
        val a = pinned()
        assertEquals(UserScriptPin.Change.New, UserScriptPin.compare(null, a))
        assertEquals(UserScriptPin.Change.Same, UserScriptPin.compare(a, pinned()))
        val codeChanged = UserScriptPin.compare(a, pinned(src + "\nalert(2)"))
        assertTrue(codeChanged is UserScriptPin.Change.Modified)
        val reqChanged = UserScriptPin.compare(a, pinned(lib = "steal()")) as UserScriptPin.Change.Modified
        assertEquals(listOf("https://cdn.example/lib.js"), reqChanged.requiresChanged)
        assertEquals(reqChanged.oldHash, reqChanged.newHash)
    }

    @Test fun tamperingDetected() {
        val s = pinned()
        assertFalse(UserScriptPin.intact(s.copy(code = s.code + "x")))
        assertTrue(UserScriptPin.intact(s.copy(sha256 = ""))) // legacy, pinned on migration
    }

    @Test fun httpsOnly() {
        UserScriptPin.requireHttps("https://greasyfork.org/x.user.js")
        for (u in listOf("http://greasyfork.org/x.user.js", "file:///sdcard/x.js", "javascript:1", "https:///nohost", "ftp://a/b"))
            assertThrows(u, UserScriptPin.InsecureSource::class.java) { UserScriptPin.requireHttps(u) }
    }

    @Test fun paymentTrustFollowsHash() {
        val s = pinned()
        val trusted = s.copy(trustedPayment = true, trustedHash = s.bundleHash)
        assertTrue(trusted.runsOnPayment)
        assertFalse(s.runsOnPayment)
        val updated = pinned(lib = "evil()").copy(trustedPayment = true, trustedHash = s.bundleHash)
        assertFalse("trust must not survive a changed @require", updated.runsOnPayment)
    }
}
