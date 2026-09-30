package org.ivanivorontsov.tiho.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.adblock.AdblockEngine
import org.ivanivorontsov.tiho.adblock.RequestTypes
import org.ivanivorontsov.tiho.privacy.DataCleaner
import org.ivanivorontsov.tiho.privacy.Scripts
import org.ivanivorontsov.tiho.privacy.UrlCleaner
import org.ivanivorontsov.tiho.privacy.UserAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One browser tab = one WebView with all privacy/adblock hooks attached. */
@SuppressLint("SetJavaScriptEnabled")
class Tab(val browser: Browser, val incognito: Boolean, initialDesktop: Boolean) {
    val id = nextId.incrementAndGet()
    private val app get() = browser.app
    private val prefs get() = browser.app.prefs

    // --- UI state
    val url = mutableStateOf("")
    val title = mutableStateOf("")
    val progress = mutableFloatStateOf(0f)
    val loading = mutableStateOf(false)
    val canBack = mutableStateOf(false)
    val canForward = mutableStateOf(false)
    val blocked = mutableIntStateOf(0)
    val desktop = mutableStateOf(initialDesktop)
    val warning = mutableStateOf<Warning?>(null)
    val secure = mutableStateOf(false)

    sealed class Warning {
        data class NoHttps(val httpUrl: String, val host: String) : Warning()
        data class Ssl(val url: String, val reason: String) : Warning()
    }

    // --- IO-thread state
    @Volatile var pageUrl: String = ""
    private val blockedCount = AtomicInteger(0)
    val blockedHosts: MutableMap<String, Int> = ConcurrentHashMap()
    private val visitedOrigins: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scriptletOrigins = ConcurrentHashMap<String, ScriptHandler>()
    private val upgradedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var genericHandler: ScriptHandler? = null
    private val userHandlers = mutableListOf<ScriptHandler>()
    private var pageFinishedOnce = false
    private val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    val webView: WebView = createWebView()

    val isHome get() = url.value.isEmpty()

    private fun createWebView(): WebView {
        val wv = WebView(browser.activity!!)
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(DataCleaner.INCOGNITO_PROFILE)
                WebViewCompat.setProfile(wv, DataCleaner.INCOGNITO_PROFILE)
            } catch (_: Exception) {}
        }
        configure(wv)
        wv.addJavascriptInterface(Bridge(), browser.bridgeName)
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { u, ua, cd, mime, len -> browser.onDownload(this, u, ua, cd, mime, len) }
        wv.setFindListener { active, count, done -> if (done) browser.findResult.value = if (count == 0) "0/0" else "${active + 1}/$count" }
        installScripts(wv)
        return wv
    }

    fun cookieManager(): CookieManager = cookieManagerFor(webView)

    private fun cookieManagerFor(wv: WebView): CookieManager =
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            try { WebViewCompat.getProfile(wv).cookieManager } catch (_: Exception) { CookieManager.getInstance() }
        else CookieManager.getInstance()

    private fun storage(): WebStorage =
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            try { WebViewCompat.getProfile(webView).webStorage } catch (_: Exception) { WebStorage.getInstance() }
        else WebStorage.getInstance()

    fun configure(wv: WebView = webView) {
        val s = wv.settings
        s.javaScriptEnabled = prefs.javascript
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION") run { s.allowFileAccessFromFileURLs = false; s.allowUniversalAccessFromFileURLs = false; s.saveFormData = false; s.databaseEnabled = false }
        s.setGeolocationEnabled(true) // every request still goes through a per-site prompt
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = !prefs.blockPopups
        s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
        s.loadWithOverviewMode = true; s.useWideViewPort = true
        s.textZoom = prefs.textZoom
        s.safeBrowsingEnabled = false // no URL lookups to Google (also opted out in the manifest)
        s.userAgentString = when {
            desktop.value -> UserAgent.desktop(app)
            prefs.genericUa -> UserAgent.mobile(app)
            else -> null
        }
        if (prefs.genericUa || desktop.value) UserAgent.applyClientHints(app, s, desktop.value)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            try { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet()) } catch (_: Exception) {} // no X-Requested-With: <package>
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR))
            try { WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED) } catch (_: Exception) {}
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ENTERPRISE_AUTHENTICATION_APP_LINK_POLICY))
            try { WebSettingsCompat.setEnterpriseAuthenticationAppLinkPolicyEnabled(s, false) } catch (_: Exception) {}
        val cm = cookieManagerFor(wv)
        cm.setAcceptCookie(prefs.cookies)
        cm.setAcceptThirdPartyCookies(wv, prefs.cookies && prefs.thirdPartyCookies)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    fun config(): JSONObject = JSONObject()
        .put("bridge", browser.bridgeName).put("token", browser.token).put("seed", browser.sessionSeed)
        .put("fp", prefs.antiFp).put("webrtc", prefs.webrtcMode).put("webgl", prefs.blockWebGl).put("sensors", prefs.blockSensors)
        .put("tz", prefs.spoofTimezone).put("gpc", prefs.gpc).put("dnt", prefs.dnt).put("pings", prefs.blockPings)
        .put("referrer", prefs.strictReferrer).put("desktop", desktop.value).put("cores", 8).put("mem", 4)
        .put("major", UserAgent.chromeMajor(app)).put("adblock", prefs.adblock)
        .put("cookies", app.extensions.builtinEnabled(app.extensions.builtins[1]))
        .put("youtube", app.extensions.builtinEnabled(app.extensions.builtins[2]))
        .put("dark", app.extensions.builtinEnabled(app.extensions.builtins[3]))

    /** (Re)registers document-start scripts: privacy shims + content script + enabled userscripts. */
    fun installScripts(wv: WebView = webView) {
        if (!docStart) return
        genericHandler?.remove(); genericHandler = null
        userHandlers.forEach { it.remove() }; userHandlers.clear()
        genericHandler = WebViewCompat.addDocumentStartJavaScript(wv, Scripts.generic(config()), setOf("*"))
        for (s in app.extensions.scripts) if (s.enabled) {
            try { userHandlers += WebViewCompat.addDocumentStartJavaScript(wv, Scripts.userscript(s, browser.bridgeName, browser.token), setOf("*")) } catch (_: Exception) {}
        }
    }

    fun resetScriptlets() { scriptletOrigins.values.forEach { it.remove() }; scriptletOrigins.clear() }

    private fun origin(u: String): String? = try { URI(u).let { if (it.scheme == null || it.host == null) null else "${it.scheme}://${it.host}" + if (it.port > 0) ":${it.port}" else "" } } catch (_: Exception) { null }
    private fun hostOf(u: String?): String? { if (u == null) return null; return try { URI(u).host } catch (_: Exception) { null } }

    /** Registers adblock scriptlets for [u]'s origin *before* navigation (so they run before page JS). */
    private fun ensureScriptlets(u: String) {
        if (!docStart || !prefs.adblock) return
        val o = origin(u) ?: return
        if (!o.startsWith("http") || scriptletOrigins.containsKey(o) || app.adblock.isAllowlisted(hostOf(u))) return
        val c = app.adblock.cosmetic(u) ?: return
        if (c.scriptlets.isBlank()) return
        if (scriptletOrigins.size > 60) resetScriptlets()
        try { scriptletOrigins[o] = WebViewCompat.addDocumentStartJavaScript(webView, Scripts.scriptlets(c.scriptlets), setOf(o)) } catch (_: Exception) {}
    }

    private fun headers(): Map<String, String> = buildMap {
        if (prefs.dnt) put("DNT", "1")
        if (prefs.gpc) put("Sec-GPC", "1")
    }

    /** Applies link-tracking protection + HTTPS-only to a top-level URL. */
    fun prepare(raw: String): String {
        var u = UrlCleaner.clean(raw, prefs.stripParams, prefs.unwrapRedirects)
        if (prefs.stripParams && prefs.adblock) {
            val d = app.adblock.engine?.check(u, u, "document")
            if (d is AdblockEngine.Decision.Rewrite) u = d.url
        }
        if (prefs.httpsOnly && u.startsWith("http://")) {
            val h = hostOf(u)
            if (h != null && h !in browser.httpAllowed && !isLocal(h)) { upgradedHosts += h; u = "https://" + u.removePrefix("http://") }
        }
        return u
    }

    private fun isLocal(h: String) = h == "localhost" || h.endsWith(".local") || h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("127.")

    fun load(raw: String) {
        warning.value = null
        val u = prepare(raw)
        url.value = u
        ensureScriptlets(u)
        webView.loadUrl(u, headers())
    }

    fun loadHttpAnyway(httpUrl: String) {
        hostOf(httpUrl)?.let { browser.httpAllowed += it; upgradedHosts -= it }
        warning.value = null
        url.value = httpUrl
        webView.loadUrl(httpUrl, headers())
    }

    fun reload() { warning.value = null; if (isHome) return; webView.reload() }
    fun stop() = webView.stopLoading()

    fun setDesktop(on: Boolean) {
        desktop.value = on
        configure(); installScripts(); resetScriptlets()
        if (!isHome) webView.reload()
    }

    fun onClosed() {
        if (prefs.clearOnTabClose || incognito) {
            val stillOpen = browser.tabs.filter { it !== this && it.incognito == incognito }.flatMap { it.visitedOrigins }.toSet()
            DataCleaner.clearSites(visitedOrigins - stillOpen, cookieManager(), storage())
        }
        webView.stopLoading()
        webView.destroy()
    }

    private fun countBlock(host: String?) {
        val n = blockedCount.incrementAndGet()
        if (host != null) blockedHosts.merge(host, 1, Int::plus)
        browser.main.post { blocked.intValue = n; prefs.totalBlocked = prefs.totalBlocked + 1 }
    }

    private fun emptyResponse(mime: String) = WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Stream that fails on read → Chromium reports a network error (like a real blocked request). */
    private fun failingResponse() = WebResourceResponse("text/plain", "utf-8", object : InputStream() {
        override fun read(): Int = throw IOException("blocked by Тихо")
    })

    // ======================================================================== WebViewClient
    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            val scheme = request.url.scheme?.lowercase() ?: return true
            if (scheme != "http" && scheme != "https") {
                if (scheme in setOf("about", "data", "blob", "javascript")) return false
                browser.openExternal(this@Tab, u)
                return true
            }
            if (!request.isForMainFrame) return false
            if (u.substringBefore('?').endsWith(".user.js")) { browser.offerUserscript(u); return true }
            val current = pageUrl
            if (prefs.blockAutoRedirects && !request.hasGesture() && !request.isRedirect && pageFinishedOnce &&
                UrlCleaner.site(hostOf(current)) != UrlCleaner.site(hostOf(u)) && current.isNotEmpty()
            ) {
                browser.snack("Заблокирован автопереход на ${hostOf(u)}", "Перейти") { load(u) }
                return true
            }
            val prepared = prepare(u)
            ensureScriptlets(prepared)
            if (prepared != u && request.method.equals("GET", true)) {
                view.loadUrl(prepared, headers())
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url.toString()
            if (request.isForMainFrame) {
                pageUrl = u
                blockedCount.set(0); blockedHosts.clear()
                browser.main.post { blocked.intValue = 0 }
                origin(u)?.let { visitedOrigins += it }
                return null
            }
            if (!prefs.adblock || !u.startsWith("http")) return null
            val src = pageUrl.ifEmpty { u }
            if (app.adblock.isAllowlisted(hostOf(src))) return null
            val engine = app.adblock.engine ?: run { app.adblock.awaitReady(); app.adblock.engine } ?: return null
            val types = RequestTypes.guess(u, false, request.requestHeaders["Accept"])
            return when (val d = engine.checkAny(u, src, types)) {
                is AdblockEngine.Decision.Block -> { countBlock(request.url.host); failingResponse() }
                is AdblockEngine.Decision.Redirect -> {
                    countBlock(request.url.host)
                    redirectResponse(d.dataUrl) ?: emptyResponse("text/plain")
                }
                else -> null
            }
        }

        private fun redirectResponse(dataUrl: String): WebResourceResponse? {
            val comma = dataUrl.indexOf(',')
            if (!dataUrl.startsWith("data:") || comma < 0) return null
            val meta = dataUrl.substring(5, comma)
            val mime = meta.substringBefore(';')
            val body = dataUrl.substring(comma + 1)
            val bytes = if (meta.endsWith(";base64")) android.util.Base64.decode(body, android.util.Base64.DEFAULT) else Uri.decode(body).toByteArray()
            return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
        }

        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
            loading.value = true
            url.value = u
            pageUrl = u
            secure.value = u.startsWith("https://")
            if (!docStart) view.evaluateJavascript(Scripts.generic(config()), null)
        }

        override fun onPageFinished(view: WebView, u: String) {
            loading.value = false
            pageFinishedOnce = true
            title.value = view.title ?: ""
            canBack.value = view.canGoBack(); canForward.value = view.canGoForward()
            if (!docStart) for (s in app.extensions.scripts) if (s.enabled && s.appliesTo(u))
                view.evaluateJavascript(Scripts.userscript(s, browser.bridgeName, browser.token), null)
        }

        override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
            // History is kept only in memory by WebView for back/forward; Тихо never writes it to disk.
            url.value = u
            canBack.value = view.canGoBack(); canForward.value = view.canGoForward()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val h = request.url.host ?: return
            if (request.url.scheme == "https" && h in upgradedHosts) {
                warning.value = Warning.NoHttps("http://" + request.url.toString().removePrefix("https://"), h)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel() // never proceed on certificate errors
            val h = hostOf(error.url)
            if (h != null && h in upgradedHosts && error.url == pageUrl) {
                warning.value = Warning.NoHttps("http://" + error.url.removePrefix("https://"), h)
                return
            }
            if (error.url == pageUrl || error.url == url.value) {
                val reason = when (error.primaryError) {
                    SslError.SSL_EXPIRED -> "сертификат просрочен"
                    SslError.SSL_IDMISMATCH -> "сертификат выдан другому сайту"
                    SslError.SSL_UNTRUSTED -> "сертификат выдан недоверенным центром"
                    SslError.SSL_NOTYETVALID -> "сертификат ещё не действителен"
                    SslError.SSL_DATE_INVALID -> "неверная дата сертификата"
                    else -> "ошибка сертификата"
                }
                warning.value = Warning.Ssl(error.url, reason)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            browser.main.post { browser.recoverCrashedTab(this@Tab) }
            return true
        }
    }

    // ======================================================================== WebChromeClient
    inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) { progress.floatValue = p / 100f }
        override fun onReceivedTitle(view: WebView, t: String?) { title.value = t ?: "" }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (prefs.blockPopups && !isUserGesture) {
                browser.snack("Всплывающее окно заблокировано")
                return false
            }
            val t = browser.newTab(incognito = incognito, select = true)
            (resultMsg.obj as WebView.WebViewTransport).webView = t.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) { browser.closeTab(this@Tab) }

        override fun onPermissionRequest(request: PermissionRequest) = browser.onPermissionRequest(this@Tab, request)
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            browser.onGeolocation(origin, callback)

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            browser.showFileChooser(cb, params)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) { browser.enterFullscreen(view, callback) }
        override fun onHideCustomView() { browser.exitFullscreen() }
        override fun onConsoleMessage(m: ConsoleMessage) = !BuildConfig.DEBUG
    }

    // ======================================================================== JS bridge
    /** Exposed to pages under a random per-session name; every call requires the session token. */
    inner class Bridge {
        private fun ok(t: String?) = t == browser.token

        @JavascriptInterface
        fun cosmetic(token: String?, u: String?): String {
            if (!ok(token) || u == null) return "{}"
            if (!prefs.adblock) return "{}"
            if (app.adblock.isAllowlisted(hostOf(pageUrl.ifEmpty { u })) || app.adblock.isAllowlisted(hostOf(u))) return "{\"off\":true}"
            if (app.adblock.engine == null) app.adblock.awaitReady(2000)
            val c = app.adblock.cosmetic(u) ?: return "{}"
            val o = origin(u)
            val js = if (o != null && scriptletOrigins.containsKey(o)) "" else c.scriptlets
            return JSONObject().put("css", c.css).put("proc", JSONArray(c.procedural)).put("js", js).put("gh", c.genericHide).toString()
        }

        @JavascriptInterface
        fun hidden(token: String?, u: String?, classes: String?, ids: String?): String? {
            if (!ok(token) || u == null) return null
            val c = app.adblock.cosmetic(u) ?: return null
            val e = app.adblock.engine ?: return null
            val sel = e.hiddenSelectors(
                classes?.split(' ')?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray(),
                ids?.split(' ')?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray(),
                c.exceptions,
            )
            return if (sel.isEmpty()) null else sel.joinToString("\n")
        }

        @JavascriptInterface fun gmGet(token: String?, id: String, key: String): String? = if (ok(token)) app.extensions.gmGet(id, key) else null
        @JavascriptInterface fun gmSet(token: String?, id: String, key: String, value: String?) { if (ok(token)) app.extensions.gmSet(id, key, value) }
        @JavascriptInterface fun gmKeys(token: String?, id: String): String = if (ok(token)) JSONArray(app.extensions.gmKeys(id)).toString() else "[]"

        @JavascriptInterface fun blobChunk(token: String?, id: String, b64: String) { if (ok(token)) browser.blobChunk(id, b64) }
        @JavascriptInterface fun blobEnd(token: String?, id: String, name: String, mime: String) { if (ok(token)) browser.main.post { browser.blobEnd(id, name, mime) } }
    }

    companion object { private val nextId = AtomicInteger(0) }
}
