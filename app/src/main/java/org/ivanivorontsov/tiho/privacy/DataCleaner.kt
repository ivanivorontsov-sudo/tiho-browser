package org.ivanivorontsov.tiho.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.io.File

/** "Fire button": wipes everything WebView stores. */
object DataCleaner {
    const val INCOGNITO_PROFILE = "tiho-incognito"
    /** WebView profile «Банк» for «Безопасный режим оплаты» tabs (separate cookies/storage). */
    const val BANK_PROFILE = "tiho-bank"

    /**
     * Removes WebView's on-disk state *before* WebView is initialised in this process
     * (cookies, cache, localStorage, IndexedDB, service workers, network state, GPU cache).
     * Only safe at process start: called from Application.onCreate when «Очищать при выходе» is on.
     */
    fun wipeFilesBeforeWebViewInit(ctx: Context) {
        val data = ctx.applicationInfo.dataDir
        listOf(File(data, "app_webview"), File(ctx.cacheDir, "WebView"), File(data, "app_textures"), File(data, "app_hws_webview"))
            .forEach { it.deleteRecursively() }
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Runtime wipe via WebView APIs (used by «Выход» and «Очистить данные сейчас»). */
    fun clearAll(ctx: Context, webView: WebView?, done: (() -> Unit)? = null) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { cm.flush(); done?.invoke() }
        WebStorage.getInstance().deleteAllData()
        try {
            WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword()
            @Suppress("DEPRECATION") WebViewDatabase.getInstance(ctx).clearFormData()
        } catch (_: Exception) {}
        webView?.apply { clearCache(true); clearHistory(); clearFormData(); clearSslPreferences() }
        WebView.clearClientCertPreferences(null)
        clearIncognito()
        clearBank()
    }

    /** Clears the «Банк» profile (payment tabs). */
    fun clearBank() = clearProfile(BANK_PROFILE)

    /** Clears the separate incognito profile (cookies/storage of incognito tabs). */
    fun clearIncognito() = clearProfile(INCOGNITO_PROFILE)

    private fun clearProfile(name: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        try {
            ProfileStore.getInstance().getProfile(name)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.webStorage.deleteAllData()
            }
        } catch (_: Exception) {}
    }

    /** Best-effort per-site cleanup when a tab is closed («Очищать данные сайта при закрытии вкладки»). */
    fun clearSites(origins: Collection<String>, cm: CookieManager, storage: WebStorage) {
        for (o in origins) {
            try { storage.deleteOrigin(o) } catch (_: Exception) {}
            val cookies = cm.getCookie(o) ?: continue
            val host = try { java.net.URI(o).host } catch (_: Exception) { null } ?: continue
            val site = UrlCleaner.site(host)
            for (c in cookies.split(';')) {
                val name = c.substringBefore('=').trim()
                if (name.isEmpty()) continue
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
                cm.setCookie(o, "$expired; Path=/")
                cm.setCookie(o, "$expired; Path=/; Domain=$host")
                cm.setCookie(o, "$expired; Path=/; Domain=.$site")
            }
        }
        cm.flush()
    }
}
