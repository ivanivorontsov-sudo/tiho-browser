package org.ivanivorontsov.tiho.adblock

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Pure-JVM wrapper around the native engine (no Android dependencies → unit-testable). */
class AdblockEngine private constructor(private var ptr: Long) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()

    sealed class Decision {
        data object Allow : Decision()
        data object Block : Decision()
        data object Exception : Decision()
        data class Redirect(val dataUrl: String) : Decision()
        data class Rewrite(val url: String) : Decision()
    }

    fun check(url: String, sourceUrl: String, type: String, method: String = "GET"): Decision = lock.read {
        if (ptr == 0L) return Decision.Allow
        val r = AdblockNative.check(ptr, url, sourceUrl, type, method) ?: return Decision.Allow
        when (r[0]) {
            'B' -> Decision.Block
            'E' -> Decision.Exception
            'R' -> Decision.Redirect(r.substring(1))
            'W' -> Decision.Rewrite(r.substring(1))
            else -> Decision.Allow
        }
    }

    /** Tries each plausible request type (WebView does not tell us the real one). */
    fun checkAny(url: String, sourceUrl: String, types: List<String>): Decision {
        var last: Decision = Decision.Allow
        for (t in types) {
            val d = check(url, sourceUrl, t)
            if (d !is Decision.Allow) return d
            last = d
        }
        return last
    }

    fun cosmeticJson(url: String): String? = lock.read { if (ptr == 0L) null else AdblockNative.cosmetic(ptr, url) }

    fun hiddenSelectors(classes: Array<String>, ids: Array<String>, exceptions: Array<String>): List<String> = lock.read {
        if (ptr == 0L) return emptyList()
        AdblockNative.hiddenClassIdSelectors(ptr, classes, ids, exceptions)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun serialize(): ByteArray? = lock.read { if (ptr == 0L) null else AdblockNative.serialize(ptr) }

    override fun close() = lock.write {
        if (ptr != 0L) AdblockNative.free(ptr)
        ptr = 0L
    }

    companion object {
        fun fromLists(lists: List<Pair<String, Boolean>>, resourcesJson: String?): AdblockEngine {
            AdblockNative.load()
            var mask = 0L
            lists.forEachIndexed { i, p -> if (p.second && i < 63) mask = mask or (1L shl i) }
            val ptr = AdblockNative.create(lists.map { it.first }.toTypedArray(), mask)
            require(ptr != 0L) { "adblock engine build failed" }
            if (resourcesJson != null) AdblockNative.useResources(ptr, resourcesJson)
            return AdblockEngine(ptr)
        }

        fun fromRules(rules: String) = fromLists(listOf(rules to true), null)
    }
}

/** Guesses adblock request types from what WebView exposes (URL, Accept header, main-frame flag). */
object RequestTypes {
    private val IMG = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp", "apng")
    private val FONT = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA = setOf("mp4", "webm", "m3u8", "mp3", "ogg", "m4s", "m4a", "aac", "mpd", "ts", "wav", "flac")

    fun guess(url: String, isMainFrame: Boolean, accept: String?): List<String> {
        if (isMainFrame) return listOf("document")
        val a = accept?.lowercase() ?: ""
        if (a.startsWith("text/html")) return listOf("sub_frame")
        if (a.startsWith("image/")) return listOf("image")
        if (a.startsWith("text/css")) return listOf("stylesheet")
        if (url.startsWith("ws:") || url.startsWith("wss:")) return listOf("websocket")
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/')
        val ext = if (last.contains('.')) last.substringAfterLast('.').lowercase() else ""
        return when {
            ext == "js" || ext == "mjs" -> listOf("script")
            ext == "css" -> listOf("stylesheet")
            ext in IMG -> listOf("image")
            ext in FONT -> listOf("font")
            ext in MEDIA -> listOf("media")
            ext == "json" || ext == "xml" -> listOf("xmlhttprequest")
            else -> listOf("script", "xmlhttprequest", "image")
        }
    }
}
