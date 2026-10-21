package org.ivanivorontsov.tiho

import android.app.Application
import android.util.Log
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.data.Bookmarks
import org.ivanivorontsov.tiho.data.Downloads
import org.ivanivorontsov.tiho.ext.Extensions
import org.ivanivorontsov.tiho.gecko.Addons
import org.ivanivorontsov.tiho.gecko.Engine
import org.ivanivorontsov.tiho.security.Payments
import org.ivanivorontsov.tiho.security.Threats
import org.ivanivorontsov.tiho.update.ContentPacks
import org.ivanivorontsov.tiho.update.UpdateJob
import java.io.File

/**
 * No analytics, no crash reporting, no Google services. The app's own network use: AMO (only when you open the
 * add-on catalogue) and — if enabled — a daily check of the signed content pack / update descriptor in the public
 * tiho-updates repo (HTTPS only). Gecko telemetry, studies and crash reports are disabled by prefs.
 */
class TihoApp : Application() {
    lateinit var prefs: Prefs
    lateinit var payments: Payments
    lateinit var contentPacks: ContentPacks
    lateinit var threats: Threats
    lateinit var extensions: Extensions
    lateinit var bookmarks: Bookmarks
    lateinit var quickLinks: org.ivanivorontsov.tiho.data.QuickLinks
    lateinit var downloads: Downloads
    lateinit var history: org.ivanivorontsov.tiho.data.History
    lateinit var engine: Engine
    lateinit var addons: Addons
    var browser: Browser? = null

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs extra processes (:tab, :gpu, …) that also instantiate the Application: keep them lean.
        if (!isMainProcess()) return
        org.ivanivorontsov.tiho.browser.MainLoopGuard.install { browser?.snack("Ошибка при открытии страницы — браузер продолжает работу") }
        prefs = Prefs(this)
        migrateFrom1x()
        payments = Payments(prefs).also { it.refresh() }
        contentPacks = ContentPacks(this)
        threats = Threats(this)
        contentPacks.init()
        threats.rebuild()
        org.ivanivorontsov.tiho.dist.Dist.init(this)
        extensions = Extensions(this, prefs).also { it.load() }
        extensions.onChanged = { if (this::addons.isInitialized) addons.pushConfig() }
        bookmarks = Bookmarks(this)
        quickLinks = org.ivanivorontsov.tiho.data.QuickLinks(this)
        downloads = Downloads(this)
        history = org.ivanivorontsov.tiho.data.History(filesDir).also { it.prune(prefs.historyDays) }
        engine = Engine(this)
        addons = Addons(this)
        engine.runtimeAddons = { addons }
        engine.start()
        // Private mode: the previous session was not closed via «Выход» (process killed) → wipe now.
        if (prefs.clearOnExit && prefs.sp.getBoolean("dirty", false)) engine.clearAllData { markClean() }
        addons.start()
        prefs.onChange { engine.applySettings() }
        UpdateJob.schedule(this, prefs.contentAuto || org.ivanivorontsov.tiho.dist.Dist.appChecks(this), prefs.contentWifiOnly && !org.ivanivorontsov.tiho.dist.Dist.appChecks(this))
    }

    fun markDirty() { if (!prefs.sp.getBoolean("dirty", false)) prefs.sp.edit().putBoolean("dirty", true).apply() }
    fun markClean() { prefs.sp.edit().putBoolean("dirty", false).apply() }

    private fun isMainProcess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() == packageName else !(currentProcessName() ?: packageName).contains(':')

    private fun currentProcessName(): String? = try { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') } catch (_: Exception) { null }

    /** 1.x (WebView) leftovers: WebView profiles, adblock engine caches, filter lists. */
    private fun migrateFrom1x() {
        if (prefs.sp.getInt("data_version", 1) >= 2) return
        try {
            val data = File(applicationInfo.dataDir)
            listOf(File(data, "app_webview"), File(data, "app_textures"), File(cacheDir, "WebView"), File(filesDir, "adblock"),
                File(filesDir, "filters"), File(cacheDir, "adblock")).forEach { if (it.exists()) it.deleteRecursively() }
            File(data, "shared_prefs").listFiles()?.filter { it.name.startsWith("WebViewChromiumPrefs") }?.forEach { it.delete() }
        } catch (e: Exception) { Log.w("Tiho", "migration", e) }
        prefs.sp.edit().putInt("data_version", 2).apply()
    }
}
