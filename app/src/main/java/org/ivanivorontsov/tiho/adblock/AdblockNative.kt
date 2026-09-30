package org.ivanivorontsov.tiho.adblock

/** JNI surface of rust/tihoadblock (Brave adblock-rust 0.13). */
object AdblockNative {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val override = System.getProperty("tiho.nativeLib")
        if (override != null && java.io.File(override).exists()) System.load(override)
        else System.loadLibrary("tihoadblock")
        loaded = true
    }

    /** Builds an engine from filter list texts; bit i of [trustedMask] marks list i as trusted. */
    @JvmStatic external fun create(lists: Array<String>, trustedMask: Long): Long
    @JvmStatic external fun deserialize(data: ByteArray): Long
    @JvmStatic external fun serialize(ptr: Long): ByteArray?
    @JvmStatic external fun useResources(ptr: Long, json: String): Boolean
    /** null = no match, "B" block, "R<dataurl>" redirect, "W<url>" rewritten, "E" exception. */
    @JvmStatic external fun check(ptr: Long, url: String, source: String, type: String, method: String): String?
    @JvmStatic external fun cosmetic(ptr: Long, url: String): String?
    @JvmStatic external fun hiddenClassIdSelectors(ptr: Long, classes: Array<String>, ids: Array<String>, exceptions: Array<String>): String?
    @JvmStatic external fun free(ptr: Long)
}
