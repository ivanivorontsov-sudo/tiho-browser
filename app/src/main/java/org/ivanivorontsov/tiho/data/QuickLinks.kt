package org.ivanivorontsov.tiho.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.ivanivorontsov.tiho.security.Net
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Quick-link tile on the home page. [icon]: "letter" (coloured letter), "favicon" or "image" (PNG in tiles/[id].png). */
data class QuickLink(val id: String, val title: String, val url: String, val icon: String = ICON_LETTER, val color: Int = 0, val iconVer: Int = 0) {
    companion object {
        const val ICON_LETTER = "letter"
        const val ICON_FAVICON = "favicon"
        const val ICON_IMAGE = "image"
    }
    val letter: String get() = (title.trim().ifEmpty { QuickLinks.hostOf(url) ?: "?" }).removePrefix("www.").take(1).uppercase()
}

/**
 * Home-page tiles, persisted in SharedPreferences (order = list order) + downscaled PNG icons in filesDir/tiles.
 * Private tabs only read these (the UI never calls the mutating methods from an incognito tab).
 */
class QuickLinks(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tiho_quicklinks", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "tiles")
    val items = mutableStateListOf<QuickLink>()

    init {
        try {
            val a = JSONArray(sp.getString("items", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                items += QuickLink(it.getString("id"), it.getString("t"), it.getString("u"), it.optString("i", QuickLink.ICON_LETTER),
                    it.optInt("c", 0), it.optInt("v", 0))
            }
        } catch (_: Exception) { items.clear() }
    }

    fun iconFile(q: QuickLink) = File(dir, "${q.id}.png")

    fun add(title: String, url: String, color: Int): QuickLink {
        val q = QuickLink(UUID.randomUUID().toString(), title, url, QuickLink.ICON_LETTER, color)
        items += q; save(); return q
    }

    fun update(q: QuickLink) {
        val i = items.indexOfFirst { it.id == q.id }; if (i < 0) return
        if (q.icon == QuickLink.ICON_LETTER) iconFile(q).delete()
        items[i] = q; save()
    }

    fun remove(q: QuickLink) { items.removeAll { it.id == q.id }; iconFile(q).delete(); save() }

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
    }

    fun commitOrder() = save()

    /** Saves [bmp] (downscaled to 128 px) as the tile icon. Returns the updated tile or null. */
    fun setIcon(q: QuickLink, bmp: Bitmap, kind: String): QuickLink? {
        dir.mkdirs()
        val s = scale(bmp)
        val f = iconFile(q); val tmp = File(dir, "${q.id}.tmp")
        tmp.outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) { tmp.delete(); return null }
        val cur = items.firstOrNull { it.id == q.id } ?: q
        val n = cur.copy(icon = kind, iconVer = cur.iconVer + 1)
        update(n); return n
    }

    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("u", it.url).put("i", it.icon).put("c", it.color).put("v", it.iconVer)) }
        sp.edit().putString("items", a.toString()).apply()
    }

    companion object {
        val COLORS = intArrayOf(0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(), 0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(),
            0xFF00838F.toInt(), 0xFF4E342E.toInt(), 0xFFAD1457.toInt(), 0xFF37474F.toInt(), 0xFF558B2F.toInt())

        fun hostOf(url: String): String? = try { Uri.parse(url).host?.removePrefix("www.") } catch (_: Exception) { null }

        /** "ya.ru" → "https://ya.ru"; only http(s) URLs with a host are accepted. */
        fun normalize(input: String): String? {
            val s = input.trim(); if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s)) s else "https://$s"
            val p = try { Uri.parse(u) } catch (_: Exception) { return null }
            val sch = p.scheme?.lowercase()
            if (sch != "https" && sch != "http") return null
            if (p.host.isNullOrBlank() || !p.host!!.contains('.') && p.host != "localhost") return null
            return u
        }

        fun scale(b: Bitmap, size: Int = 128): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val side = minOf(b.width, b.height)
            val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            Canvas(out).drawBitmap(b, src, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        fun decodeSafe(bytes: ByteArray): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (o.outWidth / sample > 512 || o.outHeight / sample > 512) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /**
         * Downloads the site icon over HTTPS (Net refuses http and https→http redirects): <link rel=icon> from the
         * start page, then /apple-touch-icon.png and /favicon.ico. Blocking — call off the main thread.
         */
        fun fetchFavicon(url: String): Bitmap? {
            val host = try { Uri.parse(url).host } catch (_: Exception) { null } ?: return null
            val base = "https://$host"
            val cands = mutableListOf<String>()
            try {
                val html = Net.getText("$base/", 1024 * 1024)
                val links = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
                    .filter { Regex("rel\\s*=\\s*[\"']?[^\"'>]*icon", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    .sortedByDescending { if (it.contains("apple-touch", true)) 2 else if (it.contains("svg", true)) 0 else 1 }
                for (l in links) {
                    val h = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1) ?: continue
                    if (h.endsWith(".svg", true)) continue
                    val abs = try { java.net.URL(java.net.URL("$base/"), h.replace("&amp;", "&")).toString() } catch (_: Exception) { continue }
                    if (abs.startsWith("https://")) cands += abs
                }
            } catch (_: Exception) {}
            cands += "$base/apple-touch-icon.png"; cands += "$base/favicon.ico"
            for (c in cands.distinct().take(6)) {
                try { decodeSafe(Net.get(c, 1024 * 1024))?.let { return it } } catch (_: Exception) {}
            }
            return null
        }
    }
}
