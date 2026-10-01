package org.ivanivorontsov.tiho.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentPackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = TestSigner(7)
    private val other = TestSigner(8)
    private val now = 1_800_000_000L
    private val files = mapOf("filters/easylist.txt" to "||ads.example^\n", "js/privacy.js" to "void 0;", "payment-domains.json" to """{"domains":["x.example"]}""")

    private fun manifest(version: Long, expires: Long = now + 86400, minApp: Int = 3, content: Map<String, String> = files): String {
        val f = content.entries.joinToString(",") { (p, c) ->
            val kind = if (p.startsWith("js/")) "code" else if (p.startsWith("filters/")) "filter" else "data"
            """{"path":"$p","url":"packs/$version/$p","sha256":"${Minisign.sha256Hex(c.toByteArray())}","size":${c.toByteArray().size},"kind":"$kind"}"""
        }
        return """{"format":1,"name":"tiho-content","version":$version,"created":"2027-01-15T00:00:00Z","expires":$expires,"min_app_version":$minApp,"min_webview_major":148,"files":[$f]}"""
    }

    private fun signed(m: String, version: Long, signer: TestSigner = key) = m.toByteArray() to signer.sign(m.toByteArray(), "tiho-content v$version timestamp:$now file:manifest.json")

    private val verifier = ContentPackVerifier(listOf(key.pub), appVersionCode = 3, now = { now })

    private fun server(m: String, sig: String, serve: Map<String, String> = files) = Triple(
        { name: String -> if (name == "manifest.json") m.toByteArray() else sig.toByteArray() },
        { f: PackFile -> (serve[f.path] ?: error("404")).toByteArray() }, Unit)

    @Test fun acceptsValidManifest() {
        val (b, s) = signed(manifest(5), 5)
        val m = verifier.verifyManifest(b, s, highWater = 4)
        assertEquals(5L, m.version)
        assertEquals(148, m.minWebViewMajor)
        assertEquals(3, m.files.size)
    }

    @Test fun rejectsWrongKey() {
        val (b, s) = signed(manifest(5), 5, other)
        assertThrows(PackException::class.java) { verifier.verifyManifest(b, s, 0) }
    }

    @Test fun rejectsDowngradeAndReplay() {
        val (b, s) = signed(manifest(5), 5)
        val e = assertThrows(PackException::class.java) { verifier.verifyManifest(b, s, 6) }
        assertTrue(e.message!!.contains("откат"))
        assertEquals("уже установлена", assertThrows(PackException::class.java) { verifier.verifyManifest(b, s, 5) }.message)
    }

    @Test fun rejectsExpired() {
        val (b, s) = signed(manifest(5, expires = now - 1), 5)
        assertThrows(PackException::class.java) { verifier.verifyManifest(b, s, 0) }
    }

    @Test fun rejectsTooNewMinApp() {
        val (b, s) = signed(manifest(5, minApp = 4), 5)
        assertThrows(PackException::class.java) { verifier.verifyManifest(b, s, 0) }
    }

    @Test fun rejectsTrustedCommentForOtherVersion() {
        val m = manifest(5)
        val s = key.sign(m.toByteArray(), "tiho-content v9 timestamp:$now file:manifest.json")
        assertThrows(PackException::class.java) { verifier.verifyManifest(m.toByteArray(), s, 0) }
    }

    @Test fun rejectsModifiedManifest() {
        val (_, s) = signed(manifest(5), 5)
        val evil = manifest(5).replace("\"min_webview_major\":148", "\"min_webview_major\":1")
        assertThrows(PackException::class.java) { verifier.verifyManifest(evil.toByteArray(), s, 0) }
    }

    @Test fun rejectsBadPaths() {
        for (p in listOf("../etc/x", "/abs", "a/../../b", "A/Upper")) {
            val m = manifest(5, content = mapOf(p to "x"))
            assertThrows(p, PackException::class.java) { PackManifest.parse(m) }
        }
    }

    @Test fun installAndRollback() {
        val store = ContentPackStore(tmp.newFolder("content"))
        val up = ContentPackUpdater(store, verifier)
        var hw = 0L
        val (m1, s1) = signed(manifest(1), 1).let { String(it.first) to it.second }
        val (ft, ff) = server(m1, s1)
        assertEquals(ContentPackUpdater.Result.Installed(1), up.update(hw, true, ft, ff, { true }) { hw = it })
        assertEquals(1L, hw)
        assertEquals("void 0;", store.file("js/privacy.js")!!.readText())
        assertEquals(1L, store.installedManifest()!!.version)

        // Same version again → up to date.
        assertTrue(up.update(hw, true, ft, ff, { true }) { hw = it } is ContentPackUpdater.Result.UpToDate)

        // v2 breaks the engine → rolled back to v1, v2 never retried.
        val v2files = files + ("filters/easylist.txt" to "broken")
        val m2 = manifest(2, content = v2files)
        val s2 = key.sign(m2.toByteArray(), "tiho-content v2 timestamp:$now file:manifest.json")
        val (ft2, ff2) = server(m2, s2, v2files)
        var validations = 0
        val r = up.update(hw, true, ft2, ff2, { validations++; store.file("filters/easylist.txt")!!.readText() != "broken" }) { hw = it }
        assertTrue(r is ContentPackUpdater.Result.RolledBack)
        assertEquals(2L, hw)
        assertEquals(2, validations)
        assertEquals(1L, store.installedManifest()!!.version)
        assertEquals("||ads.example^\n", store.file("filters/easylist.txt")!!.readText())
    }

    @Test fun badFileHashLeavesCurrentPackIntact() {
        val store = ContentPackStore(tmp.newFolder("content"))
        val up = ContentPackUpdater(store, verifier)
        var hw = 0L
        val m1 = manifest(1); val s1 = key.sign(m1.toByteArray(), "tiho-content v1 timestamp:$now file:manifest.json")
        server(m1, s1).let { (ft, ff) -> up.update(hw, true, ft, ff, { true }) { hw = it } }
        val m3 = manifest(3); val s3 = key.sign(m3.toByteArray(), "tiho-content v3 timestamp:$now file:manifest.json")
        val (ft, ff) = server(m3, s3, files + ("js/privacy.js" to "evil();"))
        val r = up.update(hw, true, ft, ff, { true }) { hw = it }
        assertTrue(r is ContentPackUpdater.Result.Failed)
        assertEquals(1L, hw) // not accepted
        assertEquals(1L, store.installedManifest()!!.version)
        assertEquals("void 0;", store.file("js/privacy.js")!!.readText())
    }

    @Test fun codeSkippedWhenNotAllowed() {
        val store = ContentPackStore(tmp.newFolder("content"))
        val m = manifest(1); val s = key.sign(m.toByteArray(), "tiho-content v1 timestamp:$now file:manifest.json")
        val (ft, ff) = server(m, s)
        ContentPackUpdater(store, verifier).update(0, false, ft, ff, { true }) {}
        assertNull(store.file("js/privacy.js"))
        assertNotNull(store.file("filters/easylist.txt"))
        assertNull(store.file("../manifest.json"))
    }

    @Test fun publishToolFixtureInstalls() {
        val r = { p: String -> javaClass.classLoader!!.getResource("packfix/$p")!!.readBytes() }
        val pub = Minisign.parsePublicKey(String(r("test-primary.pub")))
        val v = ContentPackVerifier(listOf(pub), 3, now = { 1_790_838_600L })
        val store = ContentPackStore(tmp.newFolder("fx"))
        val res = ContentPackUpdater(store, v).update(0, true, { r(it) }, { f -> r(f.url) }, { true }) {}
        assertEquals(ContentPackUpdater.Result.Installed(7), res)
        assertEquals("||ads.example^\n", store.file("filters/test.txt")!!.readText())
    }
}
