package org.ivanivorontsov.tiho.browser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewRenderProcess
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.data.DownloadItem
import org.ivanivorontsov.tiho.ext.UserScript
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.privacy.Scripts
import org.ivanivorontsov.tiho.privacy.UrlCleaner
import org.ivanivorontsov.tiho.security.DownloadPolicy
import org.ivanivorontsov.tiho.security.SchemePolicy
import org.ivanivorontsov.tiho.privacy.DataCleaner
import org.ivanivorontsov.tiho.privacy.DohProxy
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class Screen { Browser, Tabs, Bookmarks, Downloads, Settings, Privacy, Filters, Extensions, About, Updates }

data class UiDialog(
    val title: String,
    val text: String,
    val confirm: String,
    val dismiss: String = "Отмена",
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit = {},
)

data class Snack(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

/** Tab manager + glue between WebView callbacks and the Compose UI. */
class Browser(val app: TihoApp) {
    var activity: Activity? = null
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool()
    val prefs get() = app.prefs

    private val rnd = SecureRandom()
    /** Random JS-bridge name + token per process: pages can't predict or forge bridge calls. */
    val bridgeName = "_" + (1..9).map { "abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(26)] }.joinToString("")
    val token = (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)] }.joinToString("")
    /** Farbling session seed (Brave-style): new every app start, never leaves the device. */
    val sessionSeed = java.lang.Long.toHexString(rnd.nextLong())

    val tabs = mutableStateListOf<Tab>()
    val current = mutableStateOf<Tab?>(null)
    val screen = mutableStateOf(Screen.Browser)
    val dialog = mutableStateOf<UiDialog?>(null)
    val snack = mutableStateOf<Snack?>(null)
    val fullscreen = mutableStateOf<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    val findResult = mutableStateOf("")
    val httpAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Hosts the user chose to open despite the phishing interstitial (this session only). */
    val threatBypass: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val homographWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val payHintDismissed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var doh: DohProxy? = null

    /** System WebView engine state (null major = provider unknown). */
    data class EngineState(val packageName: String?, val versionName: String?, val major: Int?, val minMajor: Int) {
        val outdated get() = major != null && major < minMajor
        val unknown get() = major == null
    }
    val engine = mutableStateOf(engineState())

    fun engineState(): EngineState {
        val p = try { WebViewCompat.getCurrentWebViewPackage(app) } catch (_: Exception) { null }
        val major = p?.versionName?.substringBefore('.')?.toIntOrNull()
        return EngineState(p?.packageName, p?.versionName, major, app.contentPacks.minWebViewMajor)
    }

    // Activity hooks
    var requestPermissions: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null
    var launchFileChooser: ((Intent, (Array<Uri>?) -> Unit) -> Unit)? = null

    init {
        app.prefs.onChange { main.post { onSettingsChanged() } }
        app.extensions.onChanged = { main.post { tabs.forEach { it.installScripts() } } }
        app.adblock.onReloaded = { main.post { tabs.forEach { it.resetScriptlets() } } }
    }

    fun newTab(url: String? = null, incognito: Boolean = prefs.incognitoDefault, select: Boolean = true, payment: Boolean = false): Tab {
        val t = Tab(this, incognito, prefs.desktopDefault, payment)
        val idx = current.value?.let { tabs.indexOf(it) + 1 } ?: tabs.size
        tabs.add(idx.coerceIn(0, tabs.size), t)
        if (select) { current.value = t; screen.value = Screen.Browser; updateWindowSecurity() }
        if (url != null) open(url, t)
        return t
    }

    /** URL or text from outside (intents, typed input): scheme whitelist first. */
    fun open(input: String, tab: Tab? = null, source: SchemePolicy.Source = SchemePolicy.Source.INTERNAL) {
        when (val r = SchemePolicy.check(input, source)) {
            is SchemePolicy.Result.Load -> (tab ?: current.value ?: newTab()).load(r.url)
            is SchemePolicy.Result.Text -> { if (tab != null && current.value !== tab) select(tab); navigate(r.text) }
            is SchemePolicy.Result.Blocked -> snack("Заблокировано: ${r.reason}")
        }
    }

    /** Intents (VIEW/SEND/WEB_SEARCH) from other apps: file:, content:, javascript:, data:, intent: are refused. */
    fun openFromIntent(r: SchemePolicy.Result) {
        when (r) {
            is SchemePolicy.Result.Load -> newTab(r.url)
            is SchemePolicy.Result.Text -> if (r.text.isNotBlank()) { newTab(); navigate(r.text) }
            is SchemePolicy.Result.Blocked -> { if (tabs.isEmpty()) newTab(); snack("Ссылка из другого приложения заблокирована: ${r.reason}") }
        }
    }

    fun select(t: Tab) { current.value = t; screen.value = Screen.Browser; updateWindowSecurity() }

    /**
     * Opens [url] in a «Безопасный режим оплаты» tab (separate WebView, profile «Банк», nothing of ours injected).
     * A fresh/empty tab is replaced in place; otherwise a new payment tab opens next to it.
     */
    fun openInPaymentTab(from: Tab?, url: String): Boolean {
        val n = Tab(this, from?.incognito ?: false, from?.desktop?.value ?: false, payment = true)
        val i = from?.let { tabs.indexOf(it) } ?: -1
        if (from != null && i >= 0 && (from.isHome || from.url.value.isEmpty())) {
            tabs[i] = n
            if (current.value === from) current.value = n
            try { from.webView.destroy() } catch (_: Exception) {}
        } else {
            tabs.add(if (i >= 0) i + 1 else tabs.size, n)
            current.value = n
        }
        screen.value = Screen.Browser
        n.load(url)
        updateWindowSecurity()
        return true
    }

    /** «Похоже на оплату» → remember the domain and reload the page in safe mode. */
    fun acceptPayHint(t: Tab) {
        val h = t.payHint.value ?: return
        t.payHint.value = null
        app.payments.remember(h.host)
        tabs.forEach { it.installScripts() }
        openInPaymentTab(null, t.url.value.ifEmpty { h.url })
        closeTabQuiet(t)
    }

    fun dismissPayHint(t: Tab) { t.payHint.value?.let { payHintDismissed += it.host }; t.payHint.value = null }

    /** Leave payment mode: the page reopens in a normal tab (with Тихо's protections and scripts). */
    fun exitPaymentMode(t: Tab) {
        val u = t.url.value
        val i = tabs.indexOf(t)
        if (i < 0) return
        val n = Tab(this, t.incognito, t.desktop.value)
        tabs[i] = n
        if (current.value === t) current.value = n
        t.onClosed()
        if (u.isNotEmpty()) { n.navigating.value = true; n.webView.loadUrl(u) }
        updateWindowSecurity()
    }

    private fun closeTabQuiet(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i); t.onClosed()
        if (current.value === t) current.value = tabs.lastOrNull()
    }

    /**
     * FLAG_SECURE (no screenshots / recents preview) automatically for incognito and payment tabs, plus
     * setHideOverlayWindows on Android 12+ so other apps' overlays can't cover a payment page (tapjacking).
     */
    fun updateWindowSecurity() {
        val a = activity ?: return
        val t = current.value
        val sensitive = t != null && (t.incognito || t.safePayment)
        if (prefs.flagSecure || sensitive) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) try { a.window.setHideOverlayWindows(t != null && t.safePayment) } catch (_: Exception) {}
    }

    private val refreshScripts = Runnable { tabs.forEach { it.installScripts() } }
    /** GM_setValue changed stored values: re-register userscripts (debounced) so new pages see fresh values. */
    fun scheduleScriptRefresh() { main.removeCallbacks(refreshScripts); main.postDelayed(refreshScripts, 800) }

    // ------------------------------------------------------------------ hung renderer
    private val hung = mutableSetOf<Int>()
    fun onRendererHung(t: Tab, renderer: WebViewRenderProcess?) {
        if (!hung.add(t.id)) return
        dialog.value = UiDialog("Страница не отвечает", "${UrlCleaner.site(try { java.net.URI(t.url.value).host } catch (_: Exception) { null }) ?: "Сайт"} завис. Подождать или завершить процесс страницы?",
            "Завершить", dismiss = "Подождать",
            onConfirm = { hung.remove(t.id); if (renderer?.terminate() != true) recoverCrashedTab(t) },
            onDismiss = { hung.remove(t.id) })
    }
    fun onRendererResponsive(t: Tab) { if (hung.remove(t.id)) dialog.value = null }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        t.onClosed()
        if (t.incognito && tabs.none { it.incognito }) DataCleaner.clearIncognito()
        if (t.payment && tabs.none { it.payment } && prefs.clearOnTabClose) DataCleaner.clearBank()
        if (current.value === t) current.value = tabs.getOrNull(i.coerceAtMost(tabs.size - 1))
        if (tabs.isEmpty()) newTab()
        updateWindowSecurity()
    }

    fun closeAll(incognitoOnly: Boolean = false) {
        tabs.filter { !incognitoOnly || it.incognito }.forEach { closeTab(it) }
    }

    fun recoverCrashedTab(t: Tab) {
        val u = t.url.value
        val i = tabs.indexOf(t)
        if (i < 0) return
        val n = Tab(this, t.incognito, t.desktop.value, t.payment)
        tabs[i] = n
        if (current.value === t) current.value = n
        try { t.webView.destroy() } catch (_: Exception) {}
        if (u.isNotEmpty()) n.load(u)
        snack("Страница была перезагружена после сбоя")
    }

    /** Address bar input → URL or search (javascript:, file:, content:, data: are never executed/opened). */
    fun navigate(input: String) {
        val t = current.value ?: newTab()
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when (val r = SchemePolicy.check(s, SchemePolicy.Source.TYPED)) {
            is SchemePolicy.Result.Load -> r.url
            is SchemePolicy.Result.Blocked -> { snack("Заблокировано: ${r.reason}"); return }
            is SchemePolicy.Result.Text -> if (!r.text.contains(' ') && Regex("^[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)+(:\\d+)?(/.*)?$").matches(r.text)) "https://${r.text}" else prefs.searchUrl(r.text)
        }
        t.load(url)
    }

    fun goHome() {
        val t = current.value ?: return
        t.webView.stopLoading()
        t.webView.loadUrl("about:blank")
        t.webView.clearHistory()
        t.url.value = ""; t.title.value = ""; t.warning.value = null; t.blocked.intValue = 0; t.navigating.value = false
        t.paymentContext.value = false; t.payHint.value = null; t.homograph.value = null
        updateWindowSecurity()
    }

    fun onSettingsChanged() {
        tabs.forEach { it.configure(); it.installScripts() }
        applyDoh()
        updateWindowSecurity()
        engine.value = engineState()
        org.ivanivorontsov.tiho.update.UpdateJob.schedule(app, prefs.contentAuto, prefs.contentWifiOnly)
    }

    /** DNS-over-HTTPS: local proxy + WebView ProxyController override. */
    fun applyDoh() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val want = prefs.doh
        if (want && doh == null) {
            val p = DohProxy(DohProxy.endpointFor(prefs.dohProvider)) {
                buildList { if (prefs.dnt) add("DNT: 1"); if (prefs.gpc) add("Sec-GPC: 1") }
            }
            io.execute {
                val port = p.start()
                main.post {
                    doh = p
                    ProxyController.getInstance().setProxyOverride(
                        ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(), { it.run() }, {})
                }
            }
        } else if (!want && doh != null) {
            ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
            doh?.stop(); doh = null
        }
    }

    fun restartDoh() { if (doh != null) { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}); doh?.stop(); doh = null }; applyDoh() }

    fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        main.post { snack.value = Snack(text, action, onAction) }
    }

    fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ external apps
    /**
     * intent:// and other app links from pages: sanitized (no explicit component/selector, BROWSABLE only, no URI
     * permission grants) and confirmed by the user, who sees which app will open.
     */
    fun openExternal(tab: Tab, url: String) {
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
            } ?: intent.`package`?.let { p -> try { "${pm.getApplicationInfo(p, 0).loadLabel(pm)} ($p)" } catch (_: Exception) { "$p (не установлено)" } }
        } catch (_: Exception) { null }
        val site = try { java.net.URI(tab.url.value).host } catch (_: Exception) { null } ?: "Сайт"
        dialog.value = UiDialog(
            "Открыть внешнее приложение?",
            "$site хочет открыть " + (appName?.let { "приложение «$it»" } ?: "другое приложение") + ".\n\nСсылка: ${url.take(120)}",
            "Открыть",
            onConfirm = {
                try { activity?.startActivity(intent) } catch (_: ActivityNotFoundException) {
                    if (fallback != null && fallback.startsWith("https://")) tab.load(fallback)
                    else toast("Нет приложения для этой ссылки")
                }
            },
        )
    }

    // ------------------------------------------------------------------ permissions
    private fun topSite(tab: Tab) = UrlCleaner.site(try { java.net.URI(tab.url.value).host } catch (_: Exception) { null })

    fun onPermissionRequest(tab: Tab, req: PermissionRequest) {
        main.post {
            // Requests from third-party iframes are denied by default; the user always sees the top origin.
            val reqSite = UrlCleaner.site(req.origin.host)
            val top = topSite(tab)
            if (top != null && reqSite != top) {
                req.deny()
                snack("Отклонён запрос камеры/микрофона от встроенного сайта ${req.origin.host} (страница: $top)")
                return@post
            }
            val wanted = req.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    (it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID && prefs.drm)
            }
            if (wanted.isEmpty()) { req.deny(); return@post }
            val names = wanted.joinToString(", ") {
                when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "камера"; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "микрофон"; else -> "защищённое видео (DRM)" }
            }
            dialog.value = UiDialog("Доступ для ${req.origin.host}", "Сайт ${req.origin.scheme}://${req.origin.host} запрашивает: $names. Разрешение действует только для этой страницы.", "Разрешить",
                onConfirm = {
                    val perms = buildList {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(Manifest.permission.CAMERA)
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add(Manifest.permission.RECORD_AUDIO)
                    }.toTypedArray()
                    if (perms.isEmpty()) req.grant(wanted.toTypedArray())
                    else requestPermissions?.invoke(perms) { ok -> if (ok) req.grant(wanted.toTypedArray()) else req.deny() } ?: req.deny()
                },
                onDismiss = { req.deny() })
        }
    }

    fun onGeolocation(tab: Tab, origin: String, cb: GeolocationPermissions.Callback) {
        main.post {
            val reqSite = UrlCleaner.site(try { java.net.URI(origin).host } catch (_: Exception) { null })
            val top = topSite(tab)
            if (top != null && reqSite != top) {
                cb.invoke(origin, false, false)
                snack("Отклонён запрос геолокации от встроенного сайта $origin (страница: $top)")
                return@post
            }
            dialog.value = UiDialog("Геолокация", "Сайт $origin запрашивает ваше местоположение.", "Разрешить один раз",
                onConfirm = {
                    requestPermissions?.invoke(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { ok ->
                        cb.invoke(origin, ok, false)
                    } ?: cb.invoke(origin, false, false)
                },
                onDismiss = { cb.invoke(origin, false, false) })
        }
    }

    fun showFileChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val l = launchFileChooser ?: return false
        val intent = try { params.createIntent() } catch (_: Exception) { return false }
        l(intent) { cb.onReceiveValue(it) }
        return true
    }

    fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (fullscreen.value != null) { cb.onCustomViewHidden(); return }
        fullscreenCallback = cb
        fullscreen.value = view
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    fun exitFullscreen() {
        fullscreen.value = null
        fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ------------------------------------------------------------------ userscripts from the web
    /** HTTPS only; the script and its @require files are downloaded first and their SHA-256 shown before install. */
    fun offerUserscript(url: String, expectedSha256: String? = null) {
        try { UserScriptPin.requireHttps(url) } catch (e: SecurityException) { toast(e.message ?: "Только HTTPS"); return }
        io.execute {
            try {
                val s = app.extensions.download(url, expectedSha256)
                main.post { confirmInstall(s) }
            } catch (e: Exception) { toast("Не удалось загрузить скрипт: ${e.message}") }
        }
    }

    fun confirmInstall(s: UserScript) {
        val old = app.extensions.find(s.id)
        val change = UserScriptPin.compare(old, s)
        if (change is UserScriptPin.Change.Same) { toast("«${s.name}» уже установлен, код не изменился"); return }
        val where = (s.matches + s.includes).take(5).joinToString("\n").ifBlank { "(нигде)" }
        val req = if (s.requireHashes.isEmpty()) "" else "\n\n@require:\n" + s.requireHashes.entries.joinToString("\n") { "${it.key.take(60)}\nSHA-256 ${UserScriptPin.pretty(it.value).take(39)}…" }
        val title: String
        val head: String
        if (change is UserScriptPin.Change.Modified) {
            title = "Скрипт изменился — подтвердите"
            head = "«${s.name}» ${old?.version} → ${s.version}\nБыло SHA-256: ${UserScriptPin.pretty(change.oldHash)}\nСтало SHA-256: ${UserScriptPin.pretty(change.newHash)}" +
                (if (change.requiresChanged.isNotEmpty()) "\nИзменились @require: ${change.requiresChanged.size}" else "") +
                (if (old?.trustedPayment == true) "\n\nДоверие «на оплате» будет снято." else "")
        } else {
            title = "Установить userscript?"
            head = "«${s.name}» ${s.version}\n${s.description}\n\nSHA-256: ${UserScriptPin.pretty(s.sha256)}"
        }
        dialog.value = UiDialog(title, "$head\n\nИсточник: ${s.sourceUrl}\nБудет работать на:\n$where$req\n\nНа страницах банков и оплаты скрипты не запускаются. Устанавливайте только скрипты, которым доверяете.",
            if (change is UserScriptPin.Change.Modified) "Принять изменения" else "Установить",
            onConfirm = { io.execute { app.extensions.install(s); toast("Установлено: ${s.name}") } })
    }

    // ------------------------------------------------------------------ downloads
    fun onDownload(tab: Tab, url: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val name = URLUtil.guessFileName(url, cd, mime)
        val size = if (len > 0) " (%.1f МБ)".format(len / 1048576.0) else ""
        val risk = DownloadPolicy.assess(name, url, mime)
        if (tab.payment && url.startsWith("blob:")) { toast("В режиме оплаты загрузка blob: недоступна (нет моста скриптов)"); return }
        val from = try { java.net.URI(tab.url.value).host } catch (_: Exception) { null } ?: "страница"
        dialog.value = UiDialog(
            if (risk.dangerous) "Опасный файл" else if (risk.insecure) "Незащищённая загрузка" else "Скачать файл?",
            "$name$size\nИсточник: $from" + if (risk.any) "\n\n" + DownloadPolicy.warningText(risk) else "",
            if (risk.any) "Всё равно скачать" else "Скачать", onConfirm = {
            when {
                url.startsWith("blob:") -> {
                    val id = "b" + System.nanoTime()
                    blobNames[id] = name
                    tab.webView.evaluateJavascript(blobScript(url, id, name, mime ?: "application/octet-stream"), null)
                }
                url.startsWith("data:") -> io.execute {
                    val comma = url.indexOf(',')
                    val meta = url.substring(5, comma)
                    val bytes = if (meta.endsWith(";base64")) android.util.Base64.decode(url.substring(comma + 1), 0) else Uri.decode(url.substring(comma + 1)).toByteArray()
                    main.post { saveBytes(name, meta.substringBefore(';').ifBlank { "application/octet-stream" }, bytes, "data:") }
                }
                else -> enqueue(tab, url, ua, name, mime)
            }
        })
    }

    private fun enqueue(tab: Tab, url: String, ua: String?, name: String, mime: String?) {
        try {
            val r = DownloadManager.Request(Uri.parse(url))
            tab.cookieManager().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
            r.addRequestHeader("User-Agent", tab.webView.settings.userAgentString ?: ua ?: "")
            if (prefs.dnt) r.addRequestHeader("DNT", "1")
            r.setMimeType(mime)
            r.setTitle(name)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = app.getSystemService(DownloadManager::class.java)
            val id = dm.enqueue(r)
            app.downloads.add(DownloadItem(id, name, url, System.currentTimeMillis()))
            snack("Загрузка начата: $name", "Загрузки") { screen.value = Screen.Downloads }
        } catch (e: Exception) { toast("Ошибка загрузки: ${e.message}") }
    }

    private val blobParts = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val blobNames = ConcurrentHashMap<String, String>()

    fun blobChunk(id: String, b64: String) {
        blobParts.getOrPut(id) { ByteArrayOutputStream() }.write(android.util.Base64.decode(b64, 0))
    }

    fun blobEnd(id: String, name: String, mime: String) {
        val bytes = blobParts.remove(id)?.toByteArray() ?: return
        saveBytes(blobNames.remove(id) ?: name, mime, bytes, "blob:")
    }

    private fun blobScript(url: String, id: String, name: String, mime: String) = """
        (function(){${Scripts.rpc(bridgeName, token)}
        if(!TB)return;fetch(${org.json.JSONObject.quote(url)}).then(r=>r.blob()).then(async b=>{
          const buf=new Uint8Array(await b.arrayBuffer());const CH=393216;
          for(let i=0;i<buf.length;i+=CH){let s='';const part=buf.subarray(i,i+CH);for(let j=0;j<part.length;j++)s+=String.fromCharCode(part[j]);TB.send('bc',{id:'$id',b64:btoa(s)});}
          TB.send('be',{id:'$id',name:${org.json.JSONObject.quote(name)},mime:b.type||'$mime'});}).catch(e=>console.error(e));})();
    """.trimIndent()

    private fun saveBytes(name: String, mime: String, bytes: ByteArray, src: String) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val u = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                app.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
                u
            } else {
                val f = java.io.File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
                f.writeBytes(bytes); Uri.fromFile(f)
            }
            app.downloads.add(DownloadItem(-1, name, src, System.currentTimeMillis(), uri.toString()))
            snack("Сохранено: $name", "Загрузки") { screen.value = Screen.Downloads }
        } catch (e: Exception) { toast("Не удалось сохранить: ${e.message}") }
    }

    // ------------------------------------------------------------------ exit
    /** «Выход»: closes all tabs and (if enabled) wipes all browsing data. */
    fun exit(onDone: () -> Unit) {
        val wv = current.value?.webView
        val wipe = prefs.clearOnExit
        tabs.toList().forEach { t -> tabs.remove(t); if (t.webView !== wv) try { t.webView.destroy() } catch (_: Exception) {} }
        current.value = null
        if (wipe) DataCleaner.clearAll(app, wv) { main.post { try { wv?.destroy() } catch (_: Exception) {}; onDone() } }
        else { try { wv?.destroy() } catch (_: Exception) {}; onDone() }
    }

    fun clearNow() {
        DataCleaner.clearAll(app, current.value?.webView) { toast("Все данные сайтов удалены") }
    }
}
