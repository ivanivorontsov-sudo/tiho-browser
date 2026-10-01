package org.ivanivorontsov.tiho.update

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/** Test-only minisign signer (the production signer is tools/tiho-publish.py). */
class TestSigner(seed: Int) {
    private val sk = Ed25519PrivateKeyParameters(SecureRandom())
    val keyId = ByteArray(8) { (it * 31 + seed).toByte() }
    val pub = Minisign.PublicKey(keyId, sk.generatePublicKey().encoded)

    private fun ed(msg: ByteArray): ByteArray = Ed25519Signer().run { init(true, sk); update(msg, 0, msg.size); generateSignature() }

    fun sign(data: ByteArray, trustedComment: String, prehash: Boolean = true, tamperComment: String? = null): String {
        val alg = if (prehash) "ED" else "Ed"
        val sig = ed(if (prehash) Minisign.blake2b512(data) else data)
        val global = ed(sig + trustedComment.toByteArray())
        val b64 = Base64.getEncoder()
        return "untrusted comment: test\n" + b64.encodeToString(alg.toByteArray() + keyId + sig) + "\n" +
            "trusted comment: ${tamperComment ?: trustedComment}\n" + b64.encodeToString(global) + "\n"
    }
}
