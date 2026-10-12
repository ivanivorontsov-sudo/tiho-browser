package org.ivanivorontsov.tiho.data

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class HistoryEntry(val id: Long, val url: String, val title: String, val time: Long)

/**
 * Pure history logic (unit-tested): newest first, a repeated visit to the same URL moves it to the top,
 * capped size, age-based pruning, case-insensitive search over title + URL.
 */
class HistoryLog(private val max: Int = 5000) {
    val items = ArrayList<HistoryEntry>()
    private var nextId = 1L

    fun load(list: List<HistoryEntry>) { items.clear(); items += list.sortedByDescending { it.time }; nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1 }

    /** Only http(s) pages are recorded. */
    fun record(url: String, title: String, now: Long): Boolean {
        if (!(url.startsWith("https://", true) || url.startsWith("http://", true))) return false
        val old = items.indexOfFirst { it.url == url }
        val t = title.ifBlank { items.getOrNull(old)?.title ?: "" }
        if (old >= 0) items.removeAt(old)
        items.add(0, HistoryEntry(nextId++, url, t, now))
        while (items.size > max) items.removeAt(items.size - 1)
        return true
    }

    fun updateTitle(url: String, title: String): Boolean {
        val i = items.indexOfFirst { it.url == url }
        if (i < 0 || title.isBlank() || items[i].title == title) return false
        items[i] = items[i].copy(title = title); return true
    }

    fun remove(id: Long) = items.removeAll { it.id == id }
    fun clear() = items.clear()

    /** Removes entries older than [days] days (0 = keep forever). */
    fun prune(days: Int, now: Long): Boolean {
        if (days <= 0) return false
        val limit = now - days * 24L * 3600 * 1000
        return items.removeAll { it.time < limit }
    }

    fun search(q: String): List<HistoryEntry> {
        val s = q.trim()
        if (s.isEmpty()) return items.toList()
        return items.filter { it.title.contains(s, true) || it.url.contains(s, true) }
    }

    companion object {
        fun toJson(list: List<HistoryEntry>): String = JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("id", it.id).put("u", it.url).put("t", it.title).put("ts", it.time)) }
        }.toString()
        fun fromJson(s: String): List<HistoryEntry> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getJSONObject(it) }.map { HistoryEntry(it.getLong("id"), it.getString("u"), it.optString("t"), it.getLong("ts")) }
        } catch (_: Exception) { emptyList() }
    }
}

/**
 * Browsing history (off by default). Never written for private or «Банк» tabs — the caller checks that.
 * Stored in the app's private files dir (excluded from backup / device transfer by data_extraction_rules).
 */
class History(dir: File) {
    private val file = File(dir, "history.json")
    private val log = HistoryLog()
    private val io = Executors.newSingleThreadExecutor()
    /** Compose-observable snapshot for the history screen. */
    val items = mutableStateListOf<HistoryEntry>()

    init {
        if (file.exists()) log.load(HistoryLog.fromJson(try { file.readText() } catch (_: Exception) { "[]" }))
        publish()
    }

    @Synchronized fun record(url: String, title: String) { if (log.record(url, title, System.currentTimeMillis())) changed() }
    @Synchronized fun updateTitle(url: String, title: String) { if (log.updateTitle(url, title)) changed() }
    @Synchronized fun remove(e: HistoryEntry) { log.remove(e.id); changed() }
    @Synchronized fun clear() { log.clear(); changed() }
    @Synchronized fun prune(days: Int) { if (log.prune(days, System.currentTimeMillis())) changed() }
    @Synchronized fun search(q: String): List<HistoryEntry> = log.search(q)

    private fun publish() { items.clear(); items += log.items }
    private fun changed() {
        publish()
        val snapshot = HistoryLog.toJson(log.items)
        io.execute {
            try {
                val tmp = File(file.parentFile, "history.json.tmp"); tmp.writeText(snapshot)
                if (!tmp.renameTo(file)) { file.writeText(snapshot); tmp.delete() }
            } catch (_: Exception) {}
        }
    }
}
