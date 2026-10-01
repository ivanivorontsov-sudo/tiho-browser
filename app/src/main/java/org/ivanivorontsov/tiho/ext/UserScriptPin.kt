package org.ivanivorontsov.tiho.ext

import java.security.MessageDigest

/** HTTPS-only sources + SHA-256 pinning for userscripts and their @require files. Pure JVM (unit-tested). */
object UserScriptPin {
    fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Groups of 4 for display: "1a2b 3c4d …". */
    fun pretty(h: String): String = h.chunked(4).joinToString(" ")

    class InsecureSource(url: String) : SecurityException("Разрешены только адреса https:// — $url")

    fun requireHttps(url: String) {
        val u = try { java.net.URI(url.trim()) } catch (e: Exception) { throw InsecureSource(url) }
        if (!u.scheme.equals("https", true) || u.host.isNullOrBlank()) throw InsecureSource(url)
    }

    sealed class Change {
        data object New : Change()
        data object Same : Change()
        /** Code and/or @require contents differ from what the user approved before. */
        data class Modified(val oldHash: String, val newHash: String, val requiresChanged: List<String>) : Change()
    }

    fun compare(old: UserScript?, new: UserScript): Change {
        if (old == null) return Change.New
        val codeChanged = old.sha256 != new.sha256
        val req = (old.requireHashes.keys + new.requireHashes.keys).filter { old.requireHashes[it] != new.requireHashes[it] }
        return if (!codeChanged && req.isEmpty()) Change.Same else Change.Modified(old.sha256, new.sha256, req)
    }

    /** Verifies stored code against its pinned hash (run at every load). */
    fun intact(s: UserScript): Boolean = s.sha256.isEmpty() || sha256(s.code) == s.sha256

    /** Pins a freshly downloaded script. */
    fun pin(s: UserScript, requires: Map<String, String>): UserScript {
        val reqCode = s.requires.joinToString("\n;\n") { requires[it] ?: "" }
        return s.copy(sha256 = sha256(s.code), requireCode = reqCode, requireHashes = requires.mapValues { sha256(it.value) })
    }
}
