package org.ivanivorontsov.tiho.update

import org.junit.Assert.*
import org.junit.Test

class MinisignTest {
    private val a = TestSigner(1)
    private val b = TestSigner(2)
    private val data = "hello тихо".toByteArray()

    @Test fun validPrehashedAndLegacy() {
        assertEquals("tc 1", Minisign.verify(data, a.sign(data, "tc 1"), listOf(a.pub)).trustedComment)
        assertEquals("tc 2", Minisign.verify(data, a.sign(data, "tc 2", prehash = false), listOf(b.pub, a.pub)).trustedComment)
    }

    @Test fun backupKeyAccepted() {
        assertSame(b.pub, Minisign.verify(data, b.sign(data, "x"), listOf(a.pub, b.pub)).key)
    }

    @Test fun wrongKeyRejected() {
        assertThrows(Minisign.InvalidSignature::class.java) { Minisign.verify(data, b.sign(data, "x"), listOf(a.pub)) }
        // Same key ID but different key material.
        val fake = Minisign.PublicKey(a.keyId, b.pub.key)
        assertThrows(Minisign.InvalidSignature::class.java) { Minisign.verify(data, a.sign(data, "x"), listOf(fake)) }
    }

    @Test fun modifiedDataRejected() {
        val sig = a.sign(data, "x")
        assertThrows(Minisign.InvalidSignature::class.java) { Minisign.verify(data + 1, sig, listOf(a.pub)) }
    }

    @Test fun tamperedTrustedCommentRejected() {
        val sig = a.sign(data, "tiho-content v5 timestamp:1", tamperComment = "tiho-content v9 timestamp:1")
        assertThrows(Minisign.InvalidSignature::class.java) { Minisign.verify(data, sig, listOf(a.pub)) }
    }

    @Test fun malformed() {
        for (s in listOf("", "garbage", "untrusted comment: x\nAAAA\ntrusted comment: y\nBBBB\n"))
            assertThrows(Minisign.InvalidSignature::class.java) { Minisign.verify(data, s, listOf(a.pub)) }
    }

    @Test fun pinnedProductionKeysParse() {
        val k = TrustedKeys.keys
        assertEquals(2, k.size)
        assertEquals("ED285F174C49C57B", k[0].idHex)
        assertEquals("5A83C100C35A8CFA", k[1].idHex)
        assertEquals("0B:C1:0A:D6:83:E3:1D:22:36:07:C6:08:66:18:09:CB:BA:69:47:95:C5:1C:69:59:BB:2C:0F:A9:17:0B:8D:09", k[0].fingerprint)
    }

    /** Interop: a manifest produced by tools/tiho-publish.py with a throwaway test key. */
    @Test fun publishToolInterop() {
        val r = { p: String -> javaClass.classLoader!!.getResource("packfix/$p")!!.readBytes() }
        val pub = Minisign.parsePublicKey(String(r("test-primary.pub")))
        assertEquals("F77FECC49A635BD2", pub.idHex)
        val v = Minisign.verify(r("manifest.json"), String(r("manifest.json.minisig")), listOf(pub))
        assertTrue(v.trustedComment.startsWith("tiho-content v7 "))
        val backup = Minisign.parsePublicKey(String(r("test-backup.pub")))
        assertThrows(Minisign.InvalidSignature::class.java) { Minisign.verify(r("manifest.json"), String(r("manifest.json.minisig")), listOf(backup)) }
    }
}
