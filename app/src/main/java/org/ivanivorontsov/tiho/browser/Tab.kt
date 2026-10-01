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
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.adblock.AdblockEngine
import org.ivanivorontsov.tiho.adblock.RequestTypes
import org.ivanivorontsov.tiho.privacy.DataCleaner
import org.ivanivorontsov.tiho.privacy.Scripts
import org.ivanivorontsov.tiho.privacy.UrlCleaner
import org.ivanivorontsov.tiho.privacy.UserAgent
import org.ivanivorontsov.tiho.security.Homograph
import org.ivanivorontsov.tiho.security.PaymentDomains
import org.ivanivorontsov.tiho.security.SchemePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * One browser tab = one WebView with all privacy/adblock hooks attached.
 * [payment] tabs are «Безопасный режим оплаты»: own WebView profile «Банк» (when supported), no JS bridge, no
 * userscripts (except trusted ones with a matching hash), no scriptlets/cosmetics, no fingerprint noise or UA spoofing;
 * WebRTC stays relay-only and network blocking keeps working with payment-unbreak exceptions.
 */
@SuppressLint("SetJavaScriptEnabled")
class Tab(val browser: Browser, val incognito: Boolean, initialDesktop: Boolean, val payment: Boolean = false) {
    val id = nextId.incrementAndGet()
    private val app get() = browser.app
    private val prefs get() = browser.app.prefs

    // --- UI state
    /** Committed URL only (set when a navigation commits, never from what was requested). */
    val url = mutableStateOf("")
    val title = mutableStateOf("")
    val progress = mutableFloatStateOf(0f)
    val loading = mutableStateOf(false)
    val navigating = mutableStateOf(false)
    val canBack = mutableStateOf(false)
    val canForward = mutableStateOf(false)
    val blocked = mutableIntStateOf(0)
    val desktop = mutableStateOf(initialDesktop)
    val warning = mutableStateOf<Warning?>(null)
    val secure = mutableStateOf(false)
    /** Normal tab currently showing a payment/bank page (in-place protection: our scripts are off there). */
    val paymentContext = mutableStateOf(false)
    val payHint = mutableStateOf<PayHint?>(null)
    val homograph = mutableStateOf<Homograph.Verdict?>(null)
    val thirdPartyCookies = mutableStateOf(false)
    var bridgeKind = "нет (режим оплаты)"
        private set
    var profileName: String? = null
        private set

    sealed class Warning {
        data class NoHttps(val httpUrl: String, val host: String) : Warning()
        data class Ssl(val url: String, val reason: String) : Warning()
        data class Phishing(val url: String, val host: String, val rule: String) : Warning()
    }

    data class PayHint(val host: String, val url: String, val signals: List<String>)

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

    val isHome get() = url.value.isEmpty() && !navigating.value
    val safePayment get() = payment || paymentContext.value

    private fun createWebView(): WebView {
        val wv = WebView(browser.activity!!)
        val profile = when { payment -> DataCleaner.BANK_PROFILE; incognito -> DataCleaner.INCOGNITO_PROFILE; else -> null }
        if (profile != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(profile)
                WebViewCompat.setProfile(wv, profile)
                profileName = profile
            } catch (_: Exception) {}
        }
        if (payment) {
            wv.filterTouchesWhenObscured = true // tapjacking: ignore touches while another window covers us
            thirdPartyCookies.value = prefs.paymentThirdPartyCookies
        }
        configure(wv)
        if (!payment) installBridge(wv)
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { u, ua, cd, mime, len -> browser.onDownload(this, u, ua, cd, mime, len) }
        wv.setFindListener { active, count, done -> if (done) browser.findResult.value = if (count == 0) "0/0" else "${active + 1}/$count" }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            try {
                WebViewCompat.setWebViewRenderProcessClient(wv, Executor { browser.main.post(it) }, object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) { browser.onRendererHung(this@Tab, renderer) }
                    override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) { browser.onRendererResponsive(this@Tab) }
                })
            } catch (_: Exception) {}
        }
        installScripts(wv)
        return wv
    }

    /** JS ↔ app channel: addWebMessageListener (origin-aware, async) where supported, token-checked interface otherwise. */
    private fun installBridge(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, reply ->
                val data = message.data ?: return@WebMessageListener
                val origin = sourceOrigin.toString()
                browser.io.execute {
                    val r = handleMessage(data, origin, isMainFrame)
                    if (r != null) browser.main.post { try { reply.postMessage(r) } catch (_: Exception) {} }
                }
            }
            // Only web origins get the object (no file:, content:, opaque data: frames).
            for (rules in listOf(setOf("https://*", "http://*"), setOf("*"))) {
                try {
                    WebViewCompat.addWebMessageListener(wv, browser.bridgeName, rules, listener)
                    bridgeKind = "addWebMessageListener " + rules.joinToString(", ")
                    return
                } catch (_: Exception) {}
            }
        }
        wv.addJavascriptInterface(LegacyBridge(), browser.bridgeName)
        bridgeKind = "addJavascriptInterface (старый WebView, вызовы с токеном)"
    }

    fun cookieManager(): CookieManager = cookieManagerFor(webView)

    private fun cookieManagerFor(wv: WebView): CookieManager =
        if (profileName != null) try { WebViewCompat.getProfile(wv).cookieManager } catch (_: Exception) { CookieManager.getInstance() }
        else CookieManager.getInstance()

    private fun storage(): WebStorage =
        if (profileName != null) try { WebViewCompat.getProfile(webView).webStorage } catch (_: Exception) { WebStorage.getInstance() }
        else WebStorage.getInstance()

    fun configure(wv: WebView = webView) {
        val s = wv.settings
        s.javaScriptEnabled = prefs.javascript || payment // the site's own JS is never disabled in payment mode
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION") run { s.allowFileAccessFromFileURLs = false; s.allowUniversalAccessFromFileURLs = false; s.saveFormData = false; s.databaseEnabled = false }
        s.setGeolocationEnabled(true) // every request still goes through a per-site prompt
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = !prefs.blockPopups || payment // 3-DS often opens windows from script
        s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
        s.loadWithOverviewMode = true; s.useWideViewPort = true
        s.textZoom = prefs.textZoom
        s.safeBrowsingEnabled = false // no URL lookups to Google (also opted out in the manifest)
        s.userAgentString = when {
            desktop.value -> UserAgent.desktop(app)
            payment -> null // banks fingerprint the device for anti-fraud: real UA, no Client Hints override
            prefs.genericUa -> UserAgent.mobile(app)
            else -> null
        }
        if (!payment && (prefs.genericUa || desktop.value)) UserAgent.applyClientHints(app, s, desktop.value)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            try { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet()) } catch (_: Exception) {} // no X-Requested-With: <package>
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR))
            try { WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED) } catch (_: Exception) {}
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ENTERPRISE_AUTHENTICATION_APP_LINK_POLICY))
            try { WebSettingsCompat.setEnterpriseAuthenticationAppLinkPolicyEnabled(s, false) } catch (_: Exception) {}
        val cm = cookieManagerFor(wv)
        cm.setAcceptCookie(prefs.cookies || payment)
        // Per-tab: payment tabs may allow third-party cookies for 3-D Secure iframes (option in the indicator sheet).
        cm.setAcceptThirdPartyCookies(wv, if (payment) thirdPartyCookies.value else prefs.cookies && prefs.thirdPartyCookies)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    fun setThirdPartyCookies(on: Boolean) {
        thirdPartyCookies.value = on
        cookieManagerFor(webView).setAcceptThirdPartyCookies(webView, on)
    }

    fun config(): JSONObject = JSONObject()
        .put("seed", browser.sessionSeed)
        .put("fp", prefs.antiFp).put("webrtc", prefs.webrtcMode).put("webgl", prefs.blockWebGl).put("sensors", prefs.blockSensors)
        .put("tz", prefs.spoofTimezone).put("gpc", prefs.gpc).put("dnt", prefs.dnt).put("pings", prefs.blockPings)
        .put("referrer", prefs.strictReferrer).put("desktop", desktop.value).put("cores", 8).put("mem", 4)
        .put("major", UserAgent.chromeMajor(app)).put("adblock", prefs.adblock)
        .put("cookies", app.extensions.builtinEnabled(app.extensions.builtins[1]))
        .put("youtube", app.extensions.builtinEnabled(app.extensions.builtins[2]))
        .put("dark", app.extensions.builtinEnabled(app.extensions.builtins[3]))

    private fun userscriptJs(s: org.ivanivorontsov.tiho.ext.UserScript, guard: String) =
        Scripts.userscript(s, guard, if (payment) null else browser.bridgeName, browser.token, app.extensions.gmAll(s.id), payment)

    /** (Re)registers document-start scripts: privacy shims + content script + enabled userscripts. */
    fun installScripts(wv: WebView = webView) {
        if (!docStart) return
        genericHandler?.remove(); genericHandler = null
        userHandlers.forEach { it.remove() }; userHandlers.clear()
        val guard = app.payments.guardJs()
        genericHandler = WebViewCompat.addDocumentStartJavaScript(wv,
            if (payment) Scripts.paymentShim(config()) else Scripts.generic(config(), guard, browser.bridgeName, browser.token), setOf("*"))
        for (s in app.extensions.scripts) if (s.enabled && !s.tampered && (!payment || s.runsOnPayment)) {
            try { userHandlers += WebViewCompat.addDocumentStartJavaScript(wv, userscriptJs(s, guard), setOf("*")) } catch (_: Exception) {}
        }
    }

    fun resetScriptlets() { scriptletOrigins.values.forEach { it.remove() }; scriptletOrigins.clear() }

    private fun origin(u: String): String? = try { URI(u).let { if (it.scheme == null || it.host == null) null else "${it.scheme}://${it.host}" + if (it.port > 0) ":${it.port}" else "" } } catch (_: Exception) { null }
    private fun hostOf(u: String?): String? { if (u == null) return null; return try { URI(u).host } catch (_: Exception) { null } }

    /** Registers adblock scriptlets for [u]'s origin *before* navigation (so they run before page JS). */
    private fun ensureScriptlets(u: String) {
        if (!docStart || !prefs.adblock || payment) return
        val o = origin(u) ?: return
        if (!o.startsWith("http") || scriptletOrigins.containsKey(o) || app.adblock.isAllowlisted(hostOf(u)) || app.payments.isPayment(hostOf(u))) return
        val c = app.adblock.cosmetic(u) ?: return
        if (c.scriptlets.isBlank()) return
        if (scriptletOrigins.size > 60) resetScriptlets()
        try { scriptletOrigins[o] = WebViewCompat.addDocumentStartJavaScript(webView, Scripts.scriptlets(c.scriptlets, app.payments.guardJs()), setOf(o)) } catch (_: Exception) {}
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

    /** The single entry point for app-initiated loads; enforces the scheme whitelist. */
    fun load(raw: String) {
        val u0 = when (val r = SchemePolicy.check(raw, SchemePolicy.Source.INTERNAL)) {
            is SchemePolicy.Result.Load -> r.url
            is SchemePolicy.Result.Blocked -> { browser.snack("Заблокировано: ${r.reason}"); return }
            is SchemePolicy.Result.Text -> { browser.navigate(r.text); return }
        }
        if (u0.startsWith("data:") || u0.startsWith("blob:") || u0.startsWith("intent:")) { browser.snack("Заблокировано: схема не открывается напрямую"); return }
        val u = prepare(u0)
        if (!payment && app.payments.isPayment(hostOf(u)) && browser.openInPaymentTab(this, u)) return
        warning.value = null
        navigating.value = true
        ensureScriptlets(u)
        webView.loadUrl(u, headers())
    }

    fun loadHttpAnyway(httpUrl: String) {
        hostOf(httpUrl)?.let { browser.httpAllowed += it; upgradedHosts -= it }
        warning.value = null
        navigating.value = true
        webView.loadUrl(httpUrl, headers())
    }

    fun openPhishingAnyway(w: Warning.Phishing) {
        browser.threatBypass += w.host
        warning.value = null
        navigating.value = true
        webView.loadUrl(w.url, headers())
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
            val stillOpen = browser.tabs.filter { it !== this && it.incognito == incognito && it.payment == payment }.flatMap { it.visitedOrigins }.toSet()
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

    /**
     * Response for a blocked sub-resource. WebView cannot fail a request with ERR_BLOCKED_BY_CLIENT, so we answer
     * 403 with an empty body: <script>/<img>/<link> fire `error` like in a real blocker. fetch()/XHR are
     * rejected earlier, in page JS (privacy.js), because an HTTP error would still resolve fetch().
     */
    private fun blockedResponse() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun onCommitted(view: WebView, u: String) {
        url.value = u
        pageUrl = u
        navigating.value = false
        secure.value = u.startsWith("https://")
        val h = hostOf(u)
        val pc = !payment && app.payments.isPayment(h)
        if (paymentContext.value != pc) paymentContext.value = pc
        if (payHint.value != null && payHint.value?.host != h) payHint.value = null
        val v = Homograph.check(h)
        homograph.value = if (v.suspicious) v else null
        if (v.suspicious && browser.homographWarned.add(v.ascii)) browser.snack("Осторожно: ${v.reason}. Настоящий адрес: ${v.ascii}")
        browser.updateWindowSecurity()
    }

    // ======================================================================== WebViewClient
    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            val scheme = request.url.scheme?.lowercase() ?: return true
            if (scheme != "http" && scheme != "https") {
                if (SchemePolicy.allowedInPage(scheme) || scheme == "javascript") return false // page's own navigations
                browser.openExternal(this@Tab, u)
                return true
            }
            if (!request.isForMainFrame) return false
            if (u.substringBefore('?').endsWith(".user.js")) { browser.offerUserscript(u); return true }
            val current = pageUrl
            if (!payment && prefs.blockAutoRedirects && !request.hasGesture() && !request.isRedirect && pageFinishedOnce &&
                UrlCleaner.site(hostOf(current)) != UrlCleaner.site(hostOf(u)) && current.isNotEmpty()
            ) {
                browser.snack("Заблокирован автопереход на ${hostOf(u)}", "Перейти") { load(u) }
                return true
            }
            // Payment flows are never rewritten (no param stripping / unwrapping that could break 3-DS returns).
            if (payment || app.payments.isPayment(request.url.host)) return false
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
                blockedCount.set(0); blockedHosts.clear()
                browser.main.post { blocked.intValue = 0 }
                origin(u)?.let { visitedOrigins += it }
                // Phishing / malware lists: blocking interstitial for top-level navigations.
                if (prefs.phishing && u.startsWith("http")) {
                    val h = request.url.host ?: ""
                    val hit = if (h in browser.threatBypass) null else app.adblock.threats.match(u)
                    if (hit != null) {
                        browser.main.post { warning.value = Warning.Phishing(u, h, hit.rule); navigating.value = false }
                        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("<!doctype html><meta charset=utf-8><title>Заблокировано Тихо</title>".toByteArray()))
                    }
                }
                pageUrl = u
                return null
            }
            if (!prefs.adblock || !u.startsWith("http")) return null
            val src = pageUrl.ifEmpty { u }
            val srcHost = hostOf(src)
            if (app.adblock.isAllowlisted(srcHost)) return null
            // Built-in payment-unbreak: anti-fraud / 3-DS services are never blocked on payment pages.
            if ((payment || app.payments.isPayment(srcHost)) && PaymentDomains.isAntiFraud(request.url.host)) return null
            val engine = app.adblock.engine ?: run { app.adblock.awaitReady(); app.adblock.engine } ?: return null
            val types = RequestTypes.guess(u, false, request.requestHeaders["Accept"])
            return when (val d = engine.checkAny(u, src, types)) {
                is AdblockEngine.Decision.Block -> { countBlock(request.url.host); blockedResponse() }
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
            // WebView reports onPageStarted when the main-frame navigation has committed.
            loading.value = true
            onCommitted(view, u)
            if (!docStart) view.evaluateJavascript(
                if (payment) Scripts.paymentShim(config()) else Scripts.generic(config(), app.payments.guardJs(), browser.bridgeName, browser.token), null)
        }

        override fun onPageFinished(view: WebView, u: String) {
            loading.value = false
            navigating.value = false
            pageFinishedOnce = true
            title.value = view.title ?: ""
            canBack.value = view.canGoBack(); canForward.value = view.canGoForward()
            if (!docStart) {
                val guard = app.payments.guardJs()
                for (s in app.extensions.scripts) if (s.enabled && !s.tampered && (!payment || s.runsOnPayment) && s.appliesTo(u))
                    view.evaluateJavascript(userscriptJs(s, guard), null)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
            // History is kept only in memory by WebView for back/forward; Тихо never writes it to disk.
            if (u != url.value) onCommitted(view, u)
            canBack.value = view.canGoBack(); canForward.value = view.canGoForward()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            navigating.value = false
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
            if (prefs.blockPopups && !isUserGesture && !safePayment) {
                browser.snack("Всплывающее окно заблокировано")
                return false
            }
            val t = browser.newTab(incognito = incognito, select = true, payment = payment)
            (resultMsg.obj as WebView.WebViewTransport).webView = t.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) { browser.closeTab(this@Tab) }

        override fun onPermissionRequest(request: PermissionRequest) = browser.onPermissionRequest(this@Tab, request)
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            browser.onGeolocation(this@Tab, origin, callback)

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            browser.showFileChooser(cb, params)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) { browser.enterFullscreen(view, callback) }
        override fun onHideCustomView() { browser.exitFullscreen() }
        override fun onConsoleMessage(m: ConsoleMessage) = !BuildConfig.DEBUG
    }

    // ======================================================================== JS bridge
    private fun reply(id: String, r: Any?): String = JSONObject().put("id", id).put("r", r ?: JSONObject.NULL).toString()

    /**
     * Handles one message from page-side scripts. [origin] is the sender's origin (null on the legacy interface).
     * Payment origins never get answers: our scripts don't run there, so any message from them is forged.
     */
    internal fun handleMessage(json: String, origin: String?, isMainFrame: Boolean?): String? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        if (o.optString("t") != browser.token) return null
        if (origin != null && !origin.startsWith("https://") && !origin.startsWith("http://")) return null
        val oh = hostOf(origin)
        val k = o.optString("k")
        if (oh != null && app.payments.isPayment(oh)) return null
        val id = o.optString("id")
        when (k) {
            "cosm" -> return reply(id, cosmetic(o.optString("u")))
            "hid" -> return reply(id, hidden(o.optString("u"), o.optString("c"), o.optString("i")))
            "blk" -> return reply(id, block(o.optString("u")))
            "gms" -> {
                val v = if (o.isNull("v")) null else o.optString("v")
                app.extensions.gmSet(o.optString("s"), o.optString("key"), v)
                browser.scheduleScriptRefresh()
            }
            "bc" -> browser.blobChunk(o.optString("id"), o.optString("b64"))
            "be" -> { val bid = o.optString("id"); val n = o.optString("name"); val m = o.optString("mime"); browser.main.post { browser.blobEnd(bid, n, m) } }
            "pay" -> {
                val sig = o.optJSONArray("sig")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                if (!payment && PaymentDomains.isLikelyPayment(sig)) {
                    val h = hostOf(pageUrl) ?: return null
                    if (h !in browser.payHintDismissed && !app.payments.isPayment(h)) browser.main.post {
                        if (payHint.value == null) payHint.value = PayHint(h, pageUrl, sig)
                    }
                }
            }
        }
        return null
    }

    private fun cosmetic(u: String): String {
        if (!prefs.adblock || u.isEmpty()) return "{}"
        if (app.adblock.isAllowlisted(hostOf(pageUrl.ifEmpty { u })) || app.adblock.isAllowlisted(hostOf(u))) return "{\"off\":true}"
        if (app.adblock.engine == null) app.adblock.awaitReady(2000)
        val c = app.adblock.cosmetic(u) ?: return "{}"
        val o = origin(u)
        val js = if (o != null && scriptletOrigins.containsKey(o)) "" else c.scriptlets
        return JSONObject().put("css", c.css).put("proc", JSONArray(c.procedural)).put("js", js).put("gh", c.genericHide).toString()
    }

    private fun hidden(u: String, classes: String, ids: String): String? {
        if (u.isEmpty()) return null
        val c = app.adblock.cosmetic(u) ?: return null
        val e = app.adblock.engine ?: return null
        val sel = e.hiddenSelectors(
            classes.split(' ').filter { it.isNotEmpty() }.toTypedArray(),
            ids.split(' ').filter { it.isNotEmpty() }.toTypedArray(),
            c.exceptions,
        )
        return if (sel.isEmpty()) null else sel.joinToString("\n")
    }

    /** fetch()/XHR pre-check from page JS: true → the call is rejected like ERR_BLOCKED_BY_CLIENT. */
    private fun block(u: String): Boolean {
        if (!prefs.adblock || !u.startsWith("http")) return false
        val src = pageUrl.ifEmpty { return false }
        if (app.adblock.isAllowlisted(hostOf(src))) return false
        if (app.payments.isPayment(hostOf(src)) && PaymentDomains.isAntiFraud(hostOf(u))) return false
        val e = app.adblock.engine ?: run { app.adblock.awaitReady(); app.adblock.engine } ?: return false
        val d = e.check(u, src, "xmlhttprequest")
        if (d is AdblockEngine.Decision.Block) { countBlock(hostOf(u)); return true }
        return false
    }

    /** Fallback for WebView without WEB_MESSAGE_LISTENER: one token-checked method, same message format. */
    inner class LegacyBridge {
        @JavascriptInterface fun post(json: String?): String? = if (json == null) null else handleMessage(json, null, null)
    }

    companion object { private val nextId = AtomicInteger(0) }
}
