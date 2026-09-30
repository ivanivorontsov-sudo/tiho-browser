package org.ivanivorontsov.tiho.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

class Bookmarks(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tiho_bookmarks", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Bookmark>()

    init {
        val raw = sp.getString("items", null)
        if (raw == null) {
            // Useful defaults: privacy test pages + Russian news.
            items += listOf(
                Bookmark("Проверка блокировщика", "https://adblock.turtlecute.org/"),
                Bookmark("Cover Your Tracks (EFF)", "https://coveryourtracks.eff.org/"),
                Bookmark("BrowserLeaks", "https://browserleaks.com/"),
                Bookmark("DuckDuckGo", "https://duckduckgo.com/"),
                Bookmark("Лента.ру", "https://lenta.ru/"),
            )
            save()
        } else {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.getJSONObject(i).let { items += Bookmark(it.getString("t"), it.getString("u")) }
        }
    }

    fun contains(url: String?) = url != null && items.any { it.url == url }
    fun toggle(title: String, url: String) {
        if (contains(url)) items.removeAll { it.url == url } else items.add(0, Bookmark(title.ifBlank { url }, url))
        save()
    }
    fun remove(b: Bookmark) { items.remove(b); save() }
    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        sp.edit().putString("items", a.toString()).apply()
    }
}

data class DownloadItem(val id: Long, val name: String, val url: String, val time: Long, val uri: String? = null)

class Downloads(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tiho_downloads", Context.MODE_PRIVATE)
    val items = mutableStateListOf<DownloadItem>()

    init {
        val a = JSONArray(sp.getString("items", "[]"))
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            items += DownloadItem(it.getLong("id"), it.getString("n"), it.getString("u"), it.getLong("t"), it.optString("uri").ifBlank { null })
        }
    }

    fun add(d: DownloadItem) { items.add(0, d); save() }
    fun remove(d: DownloadItem) { items.remove(d); save() }
    fun clear() { items.clear(); save() }
    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("u", it.url).put("t", it.time).put("uri", it.uri ?: "")) }
        sp.edit().putString("items", a.toString()).apply()
    }
}
