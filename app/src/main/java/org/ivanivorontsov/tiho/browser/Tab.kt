package org.ivanivorontsov.tiho.browser

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.ivanivorontsov.tiho.ext.ScriptTokens
import org.ivanivorontsov.tiho.gecko.AddonPolicy
import org.ivanivorontsov.tiho.gecko.Engine
import org.ivanivorontsov.tiho.security.Homograph
import org.ivanivorontsov.tiho.security.PaymentDomains
import org.ivanivorontsov.tiho.security.SchemePolicy
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * One tab = one GeckoSession. «Банк» tabs use their own cookie jar (contextId "tiho-bank"); incognito tabs are
 * Gecko private sessions (nothing is written to disk).
 */
class Tab(val browser: Browser, val incognito: Boolean, initialDesktop: Boolean, val payment: Boolean = false,
          /** False for sessions handed back to Gecko (onNewSession, tabs.create): Gecko opens them itself and throws
           *  "Must use an unopened GeckoSession instance" if we already did. */
          openNow: Boolean = true) {
    val id = nextId.incrementAndGet()
    private val app get() = browser.app

    val url = mutableStateOf("")
    val title = mutableStateOf("")
    val progress = mutableFloatStateOf(0f)
    val loading = mutableStateOf(false)
    val canBack = mutableStateOf(false)
    val canForward = mutableStateOf(false)
    val desktop = mutableStateOf(initialDesktop)
    val warning = mutableStateOf<Warning?>(null)
    val secure = mutableStateOf(false)
    val payHint = mutableStateOf<PayHint?>(null)
    val homograph = mutableStateOf<Homograph.Verdict?>(null)
    /** True while the page (top level) is on a payment host, also in ordinary tabs (in-page checkout redirects). */
    val paymentContext = mutableStateOf(false)
    val blocked = mutableIntStateOf(0)
    val blockedHosts = HashMap<String, Int>()
    private val phishingBypass = HashSet<String>()
    private var payHintDismissed = HashSet<String>()

    sealed class Warning {
        data class Phishing(val url: String, val host: String, val rule: String) : Warning()
    }
    data class PayHint(val host: String, val url: String, val signals: List<String>)

    val isHome get() = url.value.isEmpty() || url.value == "about:blank"
    val safePayment get() = payment || paymentContext.value

    val session: GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(incognito)
            .contextId(if (payment) Engine.BANK_CONTEXT else null)
            .useTrackingProtection(true)
            .userAgentMode(if (initialDesktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (initialDesktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .build()
    )

    init {
        session.navigationDelegate = Nav()
        session.progressDelegate = Progress()
        session.contentDelegate = Content()
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) { browser.onScroll(this@Tab, scrollY) }
        }
        session.promptDelegate = browser.prompts.delegate(this)
        session.permissionDelegate = browser.permissions.delegate(this)
        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                blocked.intValue++
                try { Uri.parse(event.uri).host?.let { h -> blockedHosts[h] = (blockedHosts[h] ?: 0) + 1 } } catch (_: Exception) {}
            }
        }
        if (openNow) {
            session.open(app.engine.runtime)
            app.addons.tihoExt?.let { attachTihoExt(it) }
        } else whenOpen { app.addons.tihoExt?.let { attachTihoExt(it) } }
    }

    /** Runs [action] on the main thread once Gecko has opened this (popup) session; gives up after ~10 s. */
    private fun whenOpen(tries: Int = 0, action: () -> Unit) {
        browser.main.postDelayed({
            when {
                session.isOpen -> guard("whenOpen") { action() }
                tries < 200 && browser.tabs.contains(this) -> whenOpen(tries + 1, action)
                else -> Log.w("Tiho", "tab $id: popup session was never opened by Gecko")
            }
        }, 50)
    }

    /** Navigation callbacks must never take the app down: log and carry on. */
    private inline fun <T> guard(what: String, fallback: T, block: () -> T): T =
        try { block() } catch (e: Throwable) { Log.e("Tiho", "tab $id: $what failed", e); fallback }
    private inline fun guard(what: String, block: () -> Unit) = guard(what, Unit, block)

    /** Messages from our built-in extension's content scripts in this tab (payment heuristic, GM_setValue). */
    fun attachTihoExt(ext: WebExtension) {
        if (!session.isOpen) return
        session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? = guard("ext message", null) {
                val m = message as? JSONObject ?: return@guard null
                when (m.optString("type")) {
                    "pay" -> if (ScriptTokens.check(ScriptTokens.PAY_ID, m.optString("tok", ""))) onPaySignals(m)
                    "gm" -> {
                        val id = m.optString("id"); val key = m.optString("key")
                        // The token proves the message comes from that script's own wrapper (no cross-script writes).
                        if (app.extensions.find(id) != null && !incognito && ScriptTokens.check(id, m.optString("tok", ""))) {
                            app.extensions.gmSet(id, key, if (m.isNull("value")) null else m.optString("value"))
                            app.addons.schedulePush()
                        }
                    }
                }
                null
            }
        }, "tiho")
    }

    private fun onPaySignals(m: JSONObject) {
        if (safePayment) return
        val sig = (0 until (m.optJSONArray("signals")?.length() ?: 0)).map { m.getJSONArray("signals").getString(it) }
        if (!PaymentDomains.isLikelyPayment(sig)) return
        val host = try { Uri.parse(url.value).host } catch (_: Exception) { null } ?: return
        if (host in payHintDismissed) return
        payHint.value = PayHint(host, url.value, sig)
    }

    fun dismissPayHint() { payHint.value?.let { payHintDismissed += it.host }; payHint.value = null }

    fun load(u: String) { warning.value = null; if (u.isNotEmpty()) session.loadUri(u) }
    fun reload() = session.reload()
    fun stop() = session.stop()
    fun goBack() = session.goBack()
    fun goForward() = session.goForward()

    fun setDesktop(on: Boolean) {
        desktop.value = on
        session.settings.setUserAgentMode(if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        session.settings.setViewportMode(if (on) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        session.reload()
    }

    fun openPhishingAnyway() {
        val w = warning.value as? Warning.Phishing ?: return
        phishingBypass += w.host
        warning.value = null
        session.loadUri(w.url)
    }

    fun close() { try { session.close() } catch (_: Exception) {} }

    private inner class Nav : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, u: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) = guard("onLocationChange") {
            val s = u ?: ""
            url.value = if (s == "about:blank") "" else s
            val host = NavPolicy.hostOf(s)
            homograph.value = host?.let { Homograph.check(it) }?.takeIf { it.suspicious }
            paymentContext.value = host != null && app.payments.isPayment(host)
            if (payHint.value?.host != host) payHint.value = null
            blocked.intValue = 0; blockedHosts.clear()
            // History: opt-in, never for private or «Банк» tabs.
            if (!incognito && !payment && app.prefs.historyEnabled && s.isNotEmpty()) app.history.record(s, title.value)
            browser.onTabStateChanged(this@Tab)
        }
        override fun onCanGoBack(session: GeckoSession, v: Boolean) { canBack.value = v }
        override fun onCanGoForward(session: GeckoSession, v: Boolean) { canForward.value = v }

        override fun onLoadRequest(session: GeckoSession, req: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            guard("onLoadRequest", GeckoResult.allow()) { topLevel(req) }

        override fun onSubframeLoadRequest(session: GeckoSession, req: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            guard("onSubframeLoadRequest", GeckoResult.allow()) {
                when (val d = NavPolicy.decide(NavPolicy.Request(req.uri, topLevel = false, safePayment = safePayment), { app.threats.match(it)?.rule }, phishingBypass)) {
                    is NavPolicy.Decision.Phishing, is NavPolicy.Decision.External -> GeckoResult.deny()
                    is NavPolicy.Decision.Http -> learnThenAllow(d.host)
                    else -> GeckoResult.allow()
                }
            }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            // target=_blank / window.open / rel=noopener: Gecko opens the session we return — it must NOT be open yet.
            return try {
                val t = browser.newTabForPopup(this@Tab)
                if (t.session.isOpen) throw IllegalStateException("popup session already open")
                GeckoResult.fromValue(t.session)
            } catch (e: Throwable) {
                Log.e("Tiho", "tab $id: onNewSession($uri) failed, loading in the same tab", e)
                if (uri.isNotEmpty() && uri != "about:blank") browser.main.post { guard("popup fallback") { load(uri) } }
                GeckoResult.fromValue(null)
            }
        }
    }

    private fun topLevel(req: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
        val r = NavPolicy.Request(req.uri, req.triggerUri, url.value, topLevel = true,
            sameWindow = req.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT,
            hasUserGesture = req.hasUserGesture, safePayment = safePayment)
        return when (val d = NavPolicy.decide(r, { app.threats.match(it)?.rule }, phishingBypass)) {
            NavPolicy.Decision.Allow -> GeckoResult.allow()
            is NavPolicy.Decision.External -> { browser.onExternalScheme(this, d.url, req.hasUserGesture); GeckoResult.deny() }
            is NavPolicy.Decision.InstallXpi -> { app.addons.installFromAmo(d.slugOrUrl); GeckoResult.deny() }
            is NavPolicy.Decision.OfferUserscript -> { browser.offerUserscript(d.url); GeckoResult.deny() }
            is NavPolicy.Decision.Phishing -> { warning.value = Warning.Phishing(d.url, d.host, d.rule); GeckoResult.deny() }
            is NavPolicy.Decision.Unwrap -> { browser.main.post { guard("unwrap") { load(d.url) } }; GeckoResult.deny() }
            is NavPolicy.Decision.Http -> learnThenAllow(d.host)
        }
    }

    /** New exact payment host → push it into Gecko's quarantine / restricted sets before the document loads. */
    private fun learnThenAllow(host: String): GeckoResult<AllowOrDeny> {
        if (!(payment || app.payments.isPayment(host) || PaymentDomains.looksLikeAcs(host))) return GeckoResult.allow()
        if (!app.engine.learnPaymentHost(host)) return GeckoResult.allow()
        val r = GeckoResult<AllowOrDeny>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (done.compareAndSet(false, true)) r.complete(AllowOrDeny.ALLOW) }
        app.engine.afterPrefsApplied { finish() }
        browser.main.postDelayed({ if (!done.get()) Log.w("Tiho", "prefs for $host not confirmed in 3 s, loading anyway"); finish() }, 3000)
        return r
    }

    private inner class Progress : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, u: String) = guard("onPageStart") { loading.value = true; progress.floatValue = 0.05f; if (browser.current.value === this@Tab) browser.showToolbar(); if (!incognito) app.markDirty() }
        override fun onPageStop(session: GeckoSession, success: Boolean) { loading.value = false; progress.floatValue = 1f }
        override fun onProgressChange(session: GeckoSession, p: Int) { progress.floatValue = p / 100f }
        override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) = guard("onSecurityChange") { secure.value = info.isSecure }
    }

    private inner class Content : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, t: String?) {
            title.value = t ?: ""
            if (!incognito && !payment && app.prefs.historyEnabled && !t.isNullOrBlank()) app.history.updateTitle(url.value, t)
        }
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { browser.fullscreen.value = fullScreen }
        override fun onCloseRequest(session: GeckoSession) = guard("onCloseRequest") { browser.main.post { guard("close") { browser.closeTab(this@Tab) } } }
        override fun onContextMenu(session: GeckoSession, x: Int, y: Int, e: GeckoSession.ContentDelegate.ContextElement) = guard("onContextMenu") { browser.contextMenu(this@Tab, e) }
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) = guard("onExternalResponse") { browser.downloads.offer(this@Tab, response) }
        override fun onCrash(session: GeckoSession) { recover("Процесс страницы упал — вкладка перезапущена") }
        override fun onKill(session: GeckoSession) { recover("Система выгрузила вкладку из памяти — она перезапущена") }
    }

    private fun recover(msg: String) {
        Log.w("Tiho", "tab $id: $msg")
        try { session.close() } catch (_: Exception) {}
        try {
            session.open(app.engine.runtime)
            if (!isHome) session.loadUri(url.value)
            browser.reattach(this)
        } catch (e: Exception) { Log.e("Tiho", "recover", e) }
        browser.snack(msg)
    }

    companion object { private val nextId = AtomicInteger(0) }
}
