package org.ivanivorontsov.tiho.ext

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-run secret tokens binding native messages to their sender: each userscript gets its own token (embedded in its
 * wrapper closure, invisible to other scripts), payment detection has another. Messages with a missing / foreign token
 * are dropped, so one userscript cannot write another's GM storage or fake payment signals. Pure JVM (unit-tested).
 */
object ScriptTokens {
    const val PAY_ID = "\u0000paydetect"
    private val rnd = SecureRandom()
    private val tokens = ConcurrentHashMap<String, String>()

    fun tokenFor(id: String): String = tokens.getOrPut(id) {
        val b = ByteArray(16); rnd.nextBytes(b); b.joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison; unknown ids never match. */
    fun check(id: String, token: String?): Boolean {
        val t = tokens[id] ?: return false
        if (token == null || token.length != t.length) return false
        var d = 0
        for (i in t.indices) d = d or (t[i].code xor token[i].code)
        return d == 0
    }
}
