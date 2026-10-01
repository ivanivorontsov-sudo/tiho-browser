package org.ivanivorontsov.tiho

import android.app.Application
import org.ivanivorontsov.tiho.adblock.Adblock
import org.ivanivorontsov.tiho.data.Bookmarks
import org.ivanivorontsov.tiho.data.Downloads
import org.ivanivorontsov.tiho.ext.Extensions
import org.ivanivorontsov.tiho.privacy.DataCleaner
import org.ivanivorontsov.tiho.privacy.Scripts
import org.ivanivorontsov.tiho.security.Payments
import org.ivanivorontsov.tiho.update.AppUpdater
import org.ivanivorontsov.tiho.update.ContentPacks
import org.ivanivorontsov.tiho.update.UpdateJob

/**
 * No analytics, no crash reporting, no Google services. The app's own network use: filter lists (button), and —
 * if enabled — a daily check of the signed content pack / update.json in the public tiho-updates repo (HTTPS only).
 */
class TihoApp : Application() {
    lateinit var prefs: Prefs
    lateinit var payments: Payments
    lateinit var contentPacks: ContentPacks
    lateinit var appUpdater: AppUpdater
    lateinit var adblock: Adblock
    lateinit var extensions: Extensions
    lateinit var bookmarks: Bookmarks
    lateinit var downloads: Downloads

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Private mode: previous session's WebView data is wiped before WebView even starts
        // (covers the case where the app was killed and «Выход» never ran).
        if (prefs.clearOnExit) DataCleaner.wipeFilesBeforeWebViewInit(this)
        payments = Payments(prefs).also { it.refresh() }
        contentPacks = ContentPacks(this)
        contentPacks.init()
        appUpdater = AppUpdater(this)
        Scripts.init(this)
        adblock = Adblock(this, prefs)
        adblock.packFile = { contentPacks.file(it) }
        adblock.packVersion = { contentPacks.version }
        adblock.start()
        extensions = Extensions(this, prefs).also { it.load() }
        bookmarks = Bookmarks(this)
        downloads = Downloads(this)
        UpdateJob.schedule(this, prefs.contentAuto, prefs.contentWifiOnly)
    }
}
