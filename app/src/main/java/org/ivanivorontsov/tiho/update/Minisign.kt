package org.ivanivorontsov.tiho.update

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * Minisign (https://jedisct1.github.io/minisign/) signature verification: Ed25519, legacy ("Ed") and
 * pre-hashed BLAKE2b-512 ("ED") modes, trusted-comment global signature. Pure JVM (unit-tested); Ed25519 from
 * BouncyCastle because Android's own provider has it only on API 33+.
 */
object Minisign {
    class PublicKey(val keyId: ByteArray, val key: ByteArray) {
        init { require(keyId.size == 8 && key.size == 32) { "bad minisign public key" } }
        /** Key ID as minisign prints it (little-endian uint64, upper-case hex). */
        val idHex: String get() = keyId.reversedArray().joinToString("") { "%02X".format(it) }
        /** SHA-256 fingerprint of the raw Ed25519 key, colon-separated. */
        val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(key).joinToString(":") { "%02X".format(it) }
    }

    class Signature(val algorithm: String, val keyId: ByteArray, val signature: ByteArray, val trustedComment: String, val globalSignature: ByteArray)

    class Verified(val key: PublicKey, val trustedComment: String)

    class InvalidSignature(msg: String) : SecurityException(msg)

    private val b64 = Base64.getDecoder()

    fun parsePublicKey(text: String): PublicKey {
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("untrusted comment:") }
            ?: throw IllegalArgumentException("empty public key")
        val raw = b64.decode(line)
        require(raw.size == 42 && raw[0] == 'E'.code.toByte() && raw[1] == 'd'.code.toByte()) { "not an Ed25519 minisign key" }
        return PublicKey(raw.copyOfRange(2, 10), raw.copyOfRange(10, 42))
    }

    fun parseSignature(text: String): Signature {
        val lines = text.replace("\r", "").lines()
        if (lines.size < 4 || !lines[0].startsWith("untrusted comment:") || !lines[2].startsWith("trusted comment: "))
            throw InvalidSignature("malformed signature file")
        val raw = try { b64.decode(lines[1].trim()) } catch (e: IllegalArgumentException) { throw InvalidSignature("malformed signature") }
        if (raw.size != 74) throw InvalidSignature("malformed signature")
        val alg = String(raw, 0, 2, Charsets.US_ASCII)
        if (alg != "Ed" && alg != "ED") throw InvalidSignature("unsupported signature algorithm $alg")
        val global = try { b64.decode(lines[3].trim()) } catch (e: IllegalArgumentException) { throw InvalidSignature("malformed global signature") }
        if (global.size != 64) throw InvalidSignature("malformed global signature")
        return Signature(alg, raw.copyOfRange(2, 10), raw.copyOfRange(10, 74), lines[2].removePrefix("trusted comment: "), global)
    }

    fun blake2b512(data: ByteArray): ByteArray {
        val d = Blake2bDigest(512)
        d.update(data, 0, data.size)
        return ByteArray(64).also { d.doFinal(it, 0) }
    }

    fun ed25519Verify(key: ByteArray, message: ByteArray, sig: ByteArray): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(key, 0))
        s.update(message, 0, message.size)
        s.verifySignature(sig)
    } catch (_: Exception) { false }

    /**
     * Verifies [data] against [sigText] with one of the pinned [keys] (matched by key ID).
     * Both the data signature and the trusted-comment signature must be valid.
     */
    fun verify(data: ByteArray, sigText: String, keys: List<PublicKey>): Verified {
        val sig = parseSignature(sigText)
        val key = keys.firstOrNull { it.keyId.contentEquals(sig.keyId) }
            ?: throw InvalidSignature("signed by an unknown key")
        val msg = if (sig.algorithm == "ED") blake2b512(data) else data
        if (!ed25519Verify(key.key, msg, sig.signature)) throw InvalidSignature("signature does not match")
        val tc = sig.trustedComment.toByteArray(Charsets.UTF_8)
        if (!ed25519Verify(key.key, sig.signature + tc, sig.globalSignature)) throw InvalidSignature("trusted comment signature does not match")
        return Verified(key, sig.trustedComment)
    }

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
