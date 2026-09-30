package org.ivanivorontsov.tiho.adblock

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.ivanivorontsov.tiho.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FilterList(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val bundled: Boolean = true,
    val trusted: Boolean = true,
    val group: String = "ads",
)

/** Cosmetic data for one hostname, pre-rendered for the content script. */
class Cosmetic(val css: String, val procedural: String, val exceptions: Array<String>, val scriptlets: String, val genericHide: Boolean)

/** Owns the adblock-rust engine: builds it from bundled/updated lists, updates lists, answers queries. */
class Adblock(private val ctx: Context, private val prefs: Prefs) {
    @Volatile var engine: AdblockEngine? = null
        private set
    @Volatile var status: String = "Загрузка фильтров…"
        private set
    @Volatile var ruleStats: String = ""
        private set
    private val ready = CountDownLatch(1)
    private val exec = Executors.newSingleThreadExecutor()
    private val cosmeticCache = LruCache<String, Cosmetic>(64)
    private val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
    var onReloaded: (() -> Unit)? = null

    fun start() { exec.execute { rebuild() } }

    /** Waits (briefly) for the first engine so early page loads are already filtered. */
    fun awaitReady(ms: Long = 4000) { ready.await(ms, TimeUnit.MILLISECONDS) }

    private fun listText(l: FilterList): String? {
        val updated = File(dir, "${l.id}.txt")
        if (updated.exists() && updated.length() > 0) return updated.readText()
        if (!l.bundled) return null
        return try { ctx.assets.open("filters/${l.id}.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { null }
    }

    /** Serialized-engine cache: parsing ~250k rules costs seconds on slow phones, deserializing is fast. */
    private val cacheDir = File(ctx.filesDir, "engine").apply { mkdirs() }

    fun rebuild() {
        val t0 = System.currentTimeMillis()
        try {
            val enabled = prefs.enabledLists
            val active = allLists().filter { l -> l.id in enabled || (l.group == "cookies" && prefs.sp.getBoolean("ext_cookies", true)) }
            // Cache key: app version + active lists (+ mtime of updated copies) + custom rules.
            val keySrc = buildString {
                append(ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime)
                active.forEach { l -> append('|').append(l.id).append(':').append(File(dir, "${l.id}.txt").let { if (it.exists()) it.lastModified() else 0 }) }
                append('|').append(prefs.customRules.hashCode())
            }
            val key = Integer.toHexString(keySrc.hashCode()) + "-" + Integer.toHexString(keySrc.reversed().hashCode())
            val cached = File(cacheDir, "$key.bin")
            val res = ctx.assets.open("adblock/resources.json").bufferedReader().use { it.readText() }
            var e: AdblockEngine? = null
            var count = active.size
            var lines = prefs.sp.getInt("engine_rules_$key", 0)
            if (cached.exists()) {
                e = try { AdblockEngine.fromSerialized(cached.readBytes(), res) } catch (t: Throwable) { cached.delete(); null }
            }
            if (e == null) {
                val lists = mutableListOf<Pair<String, Boolean>>()
                count = 0
                for (l in active) listText(l)?.let { lists += it to l.trusted; count++ }
                if (prefs.customRules.isNotBlank()) lists += prefs.customRules to false
                e = AdblockEngine.fromLists(lists, res)
                lines = lists.sumOf { p -> p.first.count { it == '\n' } }
                try {
                    cacheDir.listFiles()?.forEach { it.delete() }
                    e.serialize()?.let { File(cacheDir, "$key.tmp").apply { writeBytes(it); renameTo(cached) } }
                    prefs.sp.edit().putInt("engine_rules_$key", lines).apply()
                } catch (_: Throwable) {}
            }
            val old = engine
            engine = e
            cosmeticCache.evictAll()
            old?.close()
            ruleStats = "$count списков, ~${lines / 1000} тыс. правил"
            status = "Готово за ${System.currentTimeMillis() - t0} мс"
            Log.i("Tiho", "adblock engine: $ruleStats in ${System.currentTimeMillis() - t0} ms (cache=${cached.exists()})")
        } catch (t: Throwable) {
            status = "Ошибка загрузки фильтров: ${t.message}"
            Log.e("Tiho", "adblock build failed", t)
        } finally {
            ready.countDown()
            onReloaded?.invoke()
        }
    }

    fun rebuildAsync(done: (() -> Unit)? = null) = exec.execute { rebuild(); done?.invoke() }

    /** Downloads fresh copies of all enabled lists (manual «Обновить фильтры»), then rebuilds. */
    fun updateLists(progress: (String) -> Unit, done: (Int, Int) -> Unit) = exec.execute {
        var ok = 0; var fail = 0
        val enabled = prefs.enabledLists
        for (l in allLists()) {
            val on = l.id in enabled || (l.group == "cookies" && prefs.sp.getBoolean("ext_cookies", true))
            if (!on) continue
            progress("Загрузка: ${l.title}")
            try {
                val c = URL(l.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                val text = c.inputStream.bufferedReader().use { it.readText() }
                if (c.responseCode == 200 && text.length > 100 && !text.trimStart().startsWith("<")) {
                    File(dir, "${l.id}.txt.tmp").apply { writeText(text); renameTo(File(dir, "${l.id}.txt")) }
                    ok++
                } else fail++
            } catch (e: Exception) { fail++ }
        }
        prefs.lastFilterUpdate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())
        progress("Сборка движка…")
        rebuild()
        done(ok, fail)
    }

    fun isAllowlisted(host: String?): Boolean {
        if (host == null) return false
        val al = prefs.allowlist
        if (al.isEmpty()) return false
        var h = host
        while (true) {
            if (h in al) return true
            val i = h!!.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun cosmetic(url: String): Cosmetic? {
        val e = engine ?: return null
        val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return null
        cosmeticCache.get(host)?.let { return it }
        val json = e.cosmeticJson(url) ?: return null
        val o = JSONObject(json)
        val sb = StringBuilder()
        val hide = o.optJSONArray("hide") ?: JSONArray()
        for (i in 0 until hide.length()) sb.append(hide.getString(i)).append("{display:none!important}\n")
        val exc = o.optJSONArray("exc") ?: JSONArray()
        val c = Cosmetic(
            css = sb.toString(),
            procedural = (o.optJSONArray("proc") ?: JSONArray()).toString(),
            exceptions = Array(exc.length()) { exc.getString(it) },
            scriptlets = o.optString("js", ""),
            genericHide = o.optBoolean("gh", false),
        )
        cosmeticCache.put(host, c)
        return c
    }

    fun allLists(): List<FilterList> = CATALOG + extraLists()

    private fun extraLists(): List<FilterList> = prefs.extraListUrls.lines().map { it.trim() }.filter { it.startsWith("http") }.map {
        FilterList("user-" + Integer.toHexString(it.hashCode()), it.substringAfterLast('/').ifBlank { it }, "Пользовательский список", it, bundled = false, trusted = false, group = "user")
    }

    companion object {
        val CATALOG = listOf(
            FilterList("easylist", "EasyList", "Основной международный список рекламы", "https://easylist.to/easylist/easylist.txt"),
            FilterList("ruadlist", "RU AdList", "Реклама на русскоязычных сайтах", "https://easylist-downloads.adblockplus.org/ruadlist.txt", group = "ru"),
            FilterList("ublock-filters", "uBlock filters", "Фильтры uBlock Origin (в т.ч. YouTube)", "https://ublockorigin.github.io/uAssets/filters/filters.min.txt"),
            FilterList("ublock-quickfixes", "uBlock – Quick fixes", "Срочные исправления uBlock Origin", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt"),
            FilterList("ublock-unbreak", "uBlock – Unbreak", "Исключения против поломки сайтов", "https://ublockorigin.github.io/uAssets/filters/unbreak.min.txt"),
            FilterList("easyprivacy", "EasyPrivacy", "Трекеры и счётчики", "https://easylist.to/easylist/easyprivacy.txt", group = "privacy"),
            FilterList("ublock-privacy", "uBlock – Privacy", "Трекинг, beacons, fingerprinting", "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt", group = "privacy"),
            FilterList("adguard-urltracking", "AdGuard URL Tracking", "Удаление трекинговых параметров из ссылок", "https://filters.adtidy.org/extension/ublock/filters/17.txt", group = "privacy"),
            FilterList("cname-ads", "AdGuard CNAME (реклама)", "Скрытая через CNAME реклама", "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_ads.txt", group = "privacy"),
            FilterList("cname-trackers", "AdGuard CNAME (трекеры, 7 МБ)", "Скрытые через CNAME трекеры — большой список, загружается по кнопке «Обновить»", "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_trackers.txt", bundled = false, group = "privacy"),
            FilterList("peterlowe", "Peter Lowe's list", "Рекламные и трекинговые серверы", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"),
            FilterList("hagezi-light", "HaGeZi Multi Light", "Реклама, трекеры, телеметрия приложений и производителей (DNS-уровень)", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt", group = "privacy"),
            FilterList("adguard-mobile", "AdGuard Mobile Ads", "Реклама мобильных сайтов и SDK — загружается по кнопке «Обновить»", "https://filters.adtidy.org/extension/ublock/filters/11.txt", bundled = false),
            FilterList("ublock-badware", "uBlock – Badware risks", "Мошеннические и опасные сайты", "https://ublockorigin.github.io/uAssets/filters/badware.min.txt", group = "security"),
            FilterList("easylist-cookie", "EasyList Cookie", "Баннеры cookie (расширение «Без cookie-баннеров»)", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", group = "cookies"),
            FilterList("ublock-cookies", "uBlock – Cookie notices", "Баннеры cookie (расширение «Без cookie-баннеров»)", "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt", group = "cookies"),
        )
    }
}
