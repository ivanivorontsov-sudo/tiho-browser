package org.ivanivorontsov.tiho

import android.app.Application
import org.ivanivorontsov.tiho.adblock.Adblock
import org.ivanivorontsov.tiho.data.Bookmarks
import org.ivanivorontsov.tiho.data.Downloads
import org.ivanivorontsov.tiho.ext.Extensions
import org.ivanivorontsov.tiho.privacy.DataCleaner
import org.ivanivorontsov.tiho.privacy.Scripts

/** No analytics, no crash reporting, no Google services: nothing here talks to the network. */
class TihoApp : Application() {
    lateinit var prefs: Prefs
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
        Scripts.init(this)
        adblock = Adblock(this, prefs).also { it.start() }
        extensions = Extensions(this, prefs).also { it.load() }
        bookmarks = Bookmarks(this)
        downloads = Downloads(this)
    }
}
