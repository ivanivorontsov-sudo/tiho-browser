package org.ivanivorontsov.tiho.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.ext.UserScript
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.privacy.UrlCleaner
import org.ivanivorontsov.tiho.security.Homograph
import org.ivanivorontsov.tiho.security.SchemePolicy
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.Executors

/** Tabs, navigation policy and UI state. Lives as long as the activity. */
class Browser(val app: TihoApp) {
    enum class Screen { BROWSER, TABS, SETTINGS, ADDONS, AMO, USERSCRIPTS, BOOKMARKS, DOWNLOADS, HISTORY, UPDATES, SECURITY, ABOUT }

    val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val prefs get() = app.prefs
    var activity: Activity? = null
    var geckoView: GeckoView? = null
    var requestPermissions: (Array<String>, (Boolean) -> Unit) -> Unit = { _, cb -> cb(false) }
    var pickFiles: (List<String>, Boolean, (List<Uri>?) -> Unit) -> Unit = { _, _, cb -> cb(null) }

    val tabs = mutableStateListOf<Tab>()
    val current = mutableStateOf<Tab?>(null)
    val screen = mutableStateOf(Screen.BROWSER)
    val dialogs = mutableStateListOf<UiDialog>()
    val snack = mutableStateOf<Snack?>(null)
    val fullscreen = mutableStateOf(false)
    val findResult = mutableStateOf<String?>(null)
    val popup = mutableStateOf<Pair<WebExtension, GeckoSession>?>(null)

    val prompts = Prompts(this)
    val permissions = Permissions(this)
    val downloads = DownloadCtl(this)

    init {
        app.browser = this
        app.addons.onTabRequest = { url -> newTab(url, select = true).session }
        // tabs.create(): like window.open, Gecko opens and loads the session itself.
        app.addons.onNewTabForGecko = { newTab(null, select = true, openNow = false).session }
        app.addons.onPopup = { ext, s -> popup.value = ext to s }
        app.addons.toast = { toast(it) }
    }

    fun show(d: UiDialog) = main.post { dialogs += d }
    fun dismiss(d: UiDialog) { dialogs.remove(d) }

    fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null) { main.post { snack.value = Snack(text, action, onAction) } }
    fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ tabs
    fun newTab(url: String? = null, incognito: Boolean = prefs.incognitoDefault, payment: Boolean = false, select: Boolean = true,
               openNow: Boolean = true): Tab {
        val t = Tab(this, incognito, prefs.desktopDefault, payment, openNow)
        tabs += t
        if (select) select(t)
        if (url != null) t.load(url)
        onBankTabsChanged()
        return t
    }

    /** window.open / target=_blank: same privacy context as the opener. */
    fun newTabForPopup(opener: Tab): Tab {
        val c = NavPolicy.popupContext(opener.incognito, opener.payment)
        return newTab(null, c.incognito, c.payment, select = true, openNow = false)
    }

    fun select(t: Tab) {
        val gv = geckoView
        current.value?.let { if (it !== t) it.session.setActive(false) }
        current.value = t
        try {
            if (gv != null && gv.session !== t.session) { gv.releaseSession(); gv.setSession(t.session) }
            t.session.setActive(true)
        } catch (e: Throwable) { android.util.Log.e("Tiho", "select tab", e) }
        screen.value = Screen.BROWSER
        updateWindowSecurity()
    }

    fun attachView(gv: GeckoView) {
        geckoView = gv
        current.value?.let { if (gv.session !== it.session) { gv.releaseSession(); gv.setSession(it.session) } }
    }

    fun reattach(t: Tab) { if (current.value === t) geckoView?.let { it.releaseSession(); it.setSession(t.session) } }

    fun attachTihoExt(ext: WebExtension) { tabs.forEach { it.attachTihoExt(ext) } }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        if (current.value === t) geckoView?.releaseSession()
        tabs.removeAt(i)
        t.close()
        if (t.incognito && tabs.none { it.incognito }) app.engine.runtime.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES)
        if (current.value === t) {
            if (tabs.isEmpty()) newTab() else select(tabs[minOf(i, tabs.size - 1)])
        }
        onBankTabsChanged()
    }

    fun closeAll() { tabs.toList().forEach { it.close() }; geckoView?.releaseSession(); tabs.clear(); current.value = null; newTab(); onBankTabsChanged() }

    private var bankOpen = false
    private fun onBankTabsChanged() {
        val open = tabs.any { it.payment }
        if (open != bankOpen) { bankOpen = open; app.addons.onBankTabs(open) }
        updateWindowSecurity()
    }

    fun onTabStateChanged(t: Tab) { if (t === current.value) updateWindowSecurity() }

    // ------------------------------------------------------------------ navigation
    fun navigate(input: String, tab: Tab? = current.value) {
        when (val r = SchemePolicy.check(input, SchemePolicy.Source.TYPED)) {
            is SchemePolicy.Result.Load -> open(r.url, tab)
            is SchemePolicy.Result.Text -> {
                val t = r.text
                if (t.isEmpty()) return
                val looksLikeHost = !t.contains(' ') && Regex("^[\\p{L}0-9.-]+\\.[\\p{L}]{2,}(:\\d+)?(/.*)?$").matches(t)
                open(if (looksLikeHost) "https://$t" else prefs.searchUrl(t), tab)
            }
            is SchemePolicy.Result.Blocked -> snack(r.reason)
        }
    }

    /** Direct navigations (typed, bookmarks, links from other apps) to a payment site open in a «Банк» tab. */
    fun open(url: String, tab: Tab? = current.value) {
        val host = try { Uri.parse(url).host } catch (_: Exception) { null }
        val pay = host != null && app.payments.isPayment(host)
        when {
            pay && tab?.payment != true -> {
                app.engine.learnPaymentHost(host)
                val t = if (tab != null && tab.isHome && !tab.incognito) { closeTabQuiet(tab); null } else null
                app.engine.afterPrefsApplied { newTab(url, incognito = false, payment = true) }
                if (t == null && tab?.isHome != true) snack("Сайт оплаты открыт в защищённой вкладке «Банк»")
            }
            tab == null -> newTab(url)
            else -> { app.engine.learnPaymentHost(host); app.engine.afterPrefsApplied { tab.load(url) } }
        }
    }

    private fun closeTabQuiet(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        if (current.value === t) geckoView?.releaseSession()
        tabs.removeAt(i); t.close()
        if (current.value === t) current.value = null
    }

    /** «Открыть в защищённой вкладке» from the menu: any site can be opened in a «Банк» tab. */
    fun openInPaymentTab(url: String) {
        app.engine.learnPaymentHost(try { Uri.parse(url).host } catch (_: Exception) { null })
        app.engine.afterPrefsApplied { newTab(url, incognito = false, payment = true) }
    }

    fun acceptPayHint(t: Tab) {
        val h = t.payHint.value ?: return
        app.payments.remember(h.host)
        app.engine.learnPaymentHost(h.host)
        t.payHint.value = null
        app.engine.afterPrefsApplied { newTab(h.url, incognito = false, payment = true) }
    }

    fun openFromIntent(r: SchemePolicy.Result) {
        when (r) {
            is SchemePolicy.Result.Load -> open(r.url, null)
            is SchemePolicy.Result.Text -> if (r.text.isNotBlank()) { newTab(); navigate(r.text) }
            is SchemePolicy.Result.Blocked -> snack("Ссылка из другого приложения заблокирована: ${r.reason}")
        }
    }

    fun goHome() { current.value?.let { it.load("about:blank"); it.url.value = "" } }

    /** Back: closes overlays first, then history, then the tab (if opened as a popup). */
    fun back(): Boolean {
        if (popup.value != null) { closePopup(); return true }
        if (screen.value != Screen.BROWSER) { screen.value = if (screen.value == Screen.AMO) Screen.ADDONS else Screen.BROWSER; return true }
        if (fullscreen.value) { current.value?.session?.exitFullScreen(); return true }
        val t = current.value ?: return false
        if (t.warning.value != null) { t.warning.value = null; return true }
        if (t.canBack.value) { t.goBack(); return true }
        if (!t.isHome) { goHome(); return true }
        return false
    }

    fun closePopup() { popup.value?.second?.close(); popup.value = null }

    // ------------------------------------------------------------------ external schemes / userscripts / context menu
    fun onExternalScheme(tab: Tab, url: String, gesture: Boolean) {
        val scheme = SchemePolicy.schemeOf(url) ?: return
        if (scheme in SchemePolicy.ALWAYS_BLOCKED) { snack("Заблокировано: ссылка $scheme: из страницы"); return }
        if (!gesture && scheme == "intent") { snack("Страница пыталась открыть приложение без нажатия — заблокировано"); return }
        main.post { openExternal(tab, url) }
    }

    private fun openExternal(tab: Tab, url: String) {
        val intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (_: Exception) { return }
        val target = intent.data?.scheme?.lowercase()
        if (target != null && target in SchemePolicy.ALWAYS_BLOCKED) { snack("Заблокировано: ссылка $target: из страницы"); return }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
        val fallback = intent.getStringExtra("browser_fallback_url")
        val pm = app.packageManager
        val appName = try {
            pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { ai ->
                if (ai.packageName == "android") null else "${ai.applicationInfo.loadLabel(pm)} (${ai.packageName})"
            }
        } catch (_: Exception) { null }
        val site = try { Homograph.display(Uri.parse(tab.url.value).host) } catch (_: Exception) { null } ?: "Сайт"
        show(UiDialog.Message("Открыть внешнее приложение?",
            "$site хочет открыть " + (appName?.let { "приложение «$it»" } ?: "другое приложение") + ".\n\nСсылка: ${url.take(120)}", "Открыть", "Отмена") { ok ->
            if (!ok) return@Message
            try { activity?.startActivity(intent) } catch (_: ActivityNotFoundException) {
                if (fallback != null && fallback.startsWith("https://")) tab.load(fallback) else toast("Нет приложения для этой ссылки")
            }
        })
    }

    fun offerUserscript(url: String) {
        if (!url.startsWith("https://")) { snack("Пользовательские скрипты устанавливаются только по HTTPS"); return }
        snack("Загрузка скрипта…")
        io.execute {
            try {
                val s = app.extensions.download(url)
                main.post { confirmUserscript(s) }
            } catch (e: Exception) { snack("Скрипт не установлен: ${e.message}") }
        }
    }

    fun confirmUserscript(s: UserScript) {
        val old = app.extensions.find(s.id)
        val change = UserScriptPin.compare(old, s)
        val text = buildString {
            append("${s.name} ${s.version}\n${s.description}\n\nИсточник: ${s.sourceUrl}\nSHA-256: ${UserScriptPin.pretty(s.sha256)}")
            if (s.requires.isNotEmpty()) append("\n@require: ${s.requires.size} (хэши закреплены)")
            if (change is UserScriptPin.Change.Modified) append("\n\nВНИМАНИЕ: код изменился по сравнению с установленным.")
            append("\n\nСкрипт не работает на страницах оплаты, пока вы явно не доверите ему эту версию.")
        }
        show(UiDialog.Message("Установить пользовательский скрипт?", text, "Установить", "Отмена") { ok ->
            if (ok) { app.extensions.install(s); app.addons.pushConfig(); toast("Скрипт установлен") }
        })
    }

    fun contextMenu(tab: Tab, e: GeckoSession.ContentDelegate.ContextElement) {
        val link = e.linkUri; val src = e.srcUri
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (link != null) {
            items += "Открыть в новой вкладке" to { open(link, null) }
            items += "Открыть в приватной вкладке" to { newTab(link, incognito = true) }
            items += "Копировать ссылку" to { copy(UrlCleaner.clean(link)) }
            items += "Поделиться ссылкой" to { share(link) }
        }
        if (src != null) {
            items += "Открыть изображение / медиа" to { newTab(src) }
            items += "Копировать адрес" to { copy(src) }
        }
        if (items.isEmpty()) return
        show(UiDialog.Menu(Homograph.display(try { Uri.parse(link ?: src).host } catch (_: Exception) { null }).ifEmpty { "Ссылка" }, items))
    }

    fun copy(text: String) {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", text))
        toast("Скопировано")
    }

    /** Shares a link without tracking parameters / redirect wrappers. */
    fun share(text: String) {
        val clean = try { UrlCleaner.clean(text) } catch (_: Exception) { text }
        try { activity?.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, clean), "Поделиться")) }
        catch (_: Exception) { toast("Не удалось поделиться") }
    }

    fun openDownload(name: String, uri: String?, mime: String?) {
        if (uri == null) { toast("Файл сохранён в папку загрузок приложения"); return }
        try {
            activity?.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime?.substringBefore(';') ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { toast("Нет приложения для открытия $name") }
    }

    // ------------------------------------------------------------------ find in page
    fun find(text: String, backwards: Boolean = false) {
        val t = current.value ?: return
        if (text.isEmpty()) { t.session.finder.clear(); findResult.value = null; return }
        t.session.finder.find(text, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0).accept({ r ->
            findResult.value = if (r == null || !r.found) "нет совпадений" else "${r.current}/${r.total}"
        }, { })
    }
    fun clearFind() { current.value?.session?.finder?.clear(); findResult.value = null }

    // ------------------------------------------------------------------ payment-safe window
    /**
     * «Банк» tabs and payment hosts: FLAG_SECURE (no screenshots / screen recording / recents preview), other apps'
     * overlays hidden (Android 12+), and touches are ignored while the window is obscured (tapjacking).
     */
    fun updateWindowSecurity() {
        val a = activity ?: return
        val safe = current.value?.safePayment == true
        if (safe || prefs.flagSecure) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) a.window.setHideOverlayWindows(safe)
        geckoView?.filterTouchesWhenObscured = safe
    }

    // ------------------------------------------------------------------ data
    fun clearNow(done: () -> Unit = {}) {
        app.engine.clearAllData { app.markClean(); main.post { toast("Данные сайтов удалены"); done() } }
    }

    /** «Выход»: closes every tab and (private mode) wipes cookies, storage, cache, permissions. */
    fun exit(done: () -> Unit) {
        geckoView?.releaseSession()
        tabs.toList().forEach { it.close() }
        tabs.clear(); current.value = null
        if (bankOpen) { bankOpen = false; app.addons.onBankTabs(false) }
        if (prefs.clearOnExit) app.engine.clearAllData { app.markClean(); main.post(done) } else done()
    }
}
