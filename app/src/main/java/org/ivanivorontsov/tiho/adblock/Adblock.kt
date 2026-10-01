package org.ivanivorontsov.tiho.adblock

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.ivanivorontsov.tiho.Prefs
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.security.Net
import org.ivanivorontsov.tiho.security.ThreatList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    /** Files from the active signed content pack (filters/<id>.txt, security/<name>.txt); null = built-in only. */
    var packFile: ((String) -> File?)? = null
    var packVersion: () -> Long = { 0L }

    /** Phishing / malware lists for the top-level navigation interstitial. */
    @Volatile var threats: ThreatList = ThreatList.EMPTY
        private set

    /** Extra lists whose content is new or changed since the user last approved it (SHA-256 pinning). */
    data class PendingList(val id: String, val title: String, val url: String, val newHash: String, val oldHash: String?)
    val pendingLists = java.util.concurrent.CopyOnWriteArrayList<PendingList>()
    var onPendingLists: (() -> Unit)? = null

    fun start() { exec.execute { rebuild(); rebuildThreats() } }

    fun rebuildThreats() {
        if (!prefs.phishing) { threats = ThreatList.EMPTY; return }
        try {
            val texts = listOf("phishing-filter", "urlhaus").map { n ->
                packFile?.invoke("security/$n.txt")?.readText()
                    ?: ctx.assets.open("security/$n.txt").bufferedReader().use { it.readText() }
            }
            threats = ThreatList.parse(*texts.toTypedArray())
            Log.i("Tiho", "threat lists: ${threats.size} entries")
        } catch (t: Throwable) { Log.e("Tiho", "threat lists failed", t) }
    }

    /** Waits (briefly) for the first engine so early page loads are already filtered. */
    fun awaitReady(ms: Long = 4000) { ready.await(ms, TimeUnit.MILLISECONDS) }

    private fun listText(l: FilterList): String? {
        val updated = File(dir, "${l.id}.txt")
        val packed = packFile?.invoke("filters/${l.id}.txt")
        // Newest wins: a signed content pack or a manual «Обновить фильтры» download.
        if (packed != null && (!updated.exists() || packed.lastModified() >= updated.lastModified())) return packed.readText()
        if (updated.exists() && updated.length() > 0) return updated.readText()
        if (!l.bundled) return null
        return try { ctx.assets.open("filters/${l.id}.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { null }
    }

    /** Serialized-engine cache: parsing ~250k rules costs seconds on slow phones, deserializing is fast. */
    private val cacheDir = File(ctx.filesDir, "engine").apply { mkdirs() }

    private fun isActive(l: FilterList, enabled: Set<String>) =
        l.id in enabled || l.id == "payment-unbreak" || (l.group == "cookies" && prefs.sp.getBoolean("ext_cookies", true))

    /** @return true if a working engine is installed. */
    @Synchronized
    fun rebuild(): Boolean {
        val t0 = System.currentTimeMillis()
        try {
            val enabled = prefs.enabledLists
            val active = allLists().filter { l -> isActive(l, enabled) }
            // Cache key: app version + active lists (+ mtime of updated copies) + custom rules.
            val keySrc = buildString {
                append(ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime)
                append("|pack").append(packVersion())
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
            // Canary: with EasyList on, a well-known ad script must be blocked, otherwise the lists are broken.
            if ("easylist" in enabled && !canaryOk(e)) {
                status = "Фильтры не работают (проверка не пройдена)"
                return false
            }
            return true
        } catch (t: Throwable) {
            status = "Ошибка загрузки фильтров: ${t.message}"
            Log.e("Tiho", "adblock build failed", t)
            return false
        } finally {
            ready.countDown()
            onReloaded?.invoke()
        }
    }

    fun rebuildAsync(done: (() -> Unit)? = null) = exec.execute { rebuild(); done?.invoke() }

    private fun pins(): JSONObject = try { JSONObject(prefs.listPins) } catch (_: Exception) { JSONObject() }

    /** User approved the new content of an extra list: pin its hash and use it. */
    fun confirmList(id: String) = exec.execute {
        val p = pendingLists.firstOrNull { it.id == id } ?: return@execute
        pendingLists.remove(p)
        val f = File(dir, "$id.txt.pending")
        if (f.exists() && UserScriptPin.sha256(f.readBytes()) == p.newHash) {
            f.renameTo(File(dir, "$id.txt"))
            prefs.listPins = pins().put(id, p.newHash).toString()
            rebuild()
        }
    }

    fun rejectList(id: String) { pendingLists.removeAll { it.id == id }; File(dir, "$id.txt.pending").delete() }

    /** Downloads fresh copies of all enabled lists (manual «Обновить фильтры»), then rebuilds. */
    fun updateLists(progress: (String) -> Unit, done: (Int, Int) -> Unit) = exec.execute {
        var ok = 0; var fail = 0
        val enabled = prefs.enabledLists
        val pins = pins()
        for (l in allLists()) {
            if (!isActive(l, enabled) || l.url.isBlank()) continue
            progress("Загрузка: ${l.title}")
            try {
                val bytes = Net.get(l.url, 32L * 1024 * 1024) // HTTPS only
                val text = String(bytes, Charsets.UTF_8)
                if (text.length > 100 && !text.trimStart().startsWith("<")) {
                    if (l.group == "user") {
                        // Third-party list: show and pin SHA-256; any change needs the user's confirmation.
                        val h = UserScriptPin.sha256(bytes)
                        val old = pins.optString(l.id).ifBlank { null }
                        if (old != h) {
                            File(dir, "${l.id}.txt.pending").writeBytes(bytes)
                            pendingLists.removeAll { it.id == l.id }
                            pendingLists += PendingList(l.id, l.title, l.url, h, old)
                            continue
                        }
                    }
                    File(dir, "${l.id}.txt.tmp").apply { writeText(text); renameTo(File(dir, "${l.id}.txt")) }
                    ok++
                } else fail++
            } catch (e: Exception) { fail++ }
        }
        if (pendingLists.isNotEmpty()) onPendingLists?.invoke()
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

    /** Extra list URLs: HTTPS only (http:// lines are ignored and reported in the UI). */
    private fun extraLists(): List<FilterList> = prefs.extraListUrls.lines().map { it.trim() }.filter { it.startsWith("https://") }.map {
        FilterList("user-" + Integer.toHexString(it.hashCode()), it.substringAfterLast('/').ifBlank { it }, "Пользовательский список", it, bundled = false, trusted = false, group = "user")
    }

    companion object {
        /** A well-known ad script must be blocked (or neutralised by a redirect resource, as uBO lists do). */
        fun canaryOk(e: AdblockEngine): Boolean =
            e.check("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://example.com/", "script").let {
                it is AdblockEngine.Decision.Block || it is AdblockEngine.Decision.Redirect
            }

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
            FilterList("payment-unbreak", "Тихо — payment-unbreak", "Встроенные исключения для антифрода и 3-D Secure на страницах оплаты (всегда включено)", "", group = "security"),
            FilterList("ublock-badware", "uBlock – Badware risks", "Мошеннические и опасные сайты", "https://ublockorigin.github.io/uAssets/filters/badware.min.txt", group = "security"),
            FilterList("easylist-cookie", "EasyList Cookie", "Баннеры cookie (расширение «Без cookie-баннеров»)", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", group = "cookies"),
            FilterList("ublock-cookies", "uBlock – Cookie notices", "Баннеры cookie (расширение «Без cookie-баннеров»)", "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt", group = "cookies"),
        )
    }
}
