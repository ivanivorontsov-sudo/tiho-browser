package org.ivanivorontsov.tiho.browser

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
class Tab(val browser: Browser, val incognito: Boolean, initialDesktop: Boolean, val payment: Boolean = false) {
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
        session.promptDelegate = browser.prompts.delegate(this)
        session.permissionDelegate = browser.permissions.delegate(this)
        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                blocked.intValue++
                try { Uri.parse(event.uri).host?.let { h -> blockedHosts[h] = (blockedHosts[h] ?: 0) + 1 } } catch (_: Exception) {}
            }
        }
        session.open(app.engine.runtime)
        app.addons.tihoExt?.let { attachTihoExt(it) }
    }

    /** Messages from our built-in extension's content scripts in this tab (payment heuristic, GM_setValue). */
    fun attachTihoExt(ext: WebExtension) {
        session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val m = message as? JSONObject ?: return null
                when (m.optString("type")) {
                    "pay" -> onPaySignals(m)
                    "gm" -> {
                        val id = m.optString("id"); val key = m.optString("key")
                        if (app.extensions.find(id) != null && !incognito) {
                            app.extensions.gmSet(id, key, if (m.isNull("value")) null else m.optString("value"))
                            app.addons.schedulePush()
                        }
                    }
                }
                return null
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

    fun load(u: String) { warning.value = null; session.loadUri(u) }
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
        override fun onLocationChange(session: GeckoSession, u: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
            val s = u ?: ""
            url.value = if (s == "about:blank") "" else s
            val host = try { Uri.parse(s).host } catch (_: Exception) { null }
            homograph.value = host?.let { Homograph.check(it) }?.takeIf { it.suspicious }
            paymentContext.value = host != null && app.payments.isPayment(host)
            if (payHint.value?.host != host) payHint.value = null
            blocked.intValue = 0; blockedHosts.clear()
            browser.onTabStateChanged(this@Tab)
        }
        override fun onCanGoBack(session: GeckoSession, v: Boolean) { canBack.value = v }
        override fun onCanGoForward(session: GeckoSession, v: Boolean) { canForward.value = v }

        override fun onLoadRequest(session: GeckoSession, req: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val u = req.uri
            val scheme = SchemePolicy.schemeOf(u) ?: return GeckoResult.allow()
            // Built-in pages and extension pages are fine; file:, content:, javascript:, chrome: etc. never load.
            if (scheme == "moz-extension" || scheme == "about" || scheme == "resource" && u.startsWith("resource://android/assets/tihoext/")) return GeckoResult.allow()
            if (!SchemePolicy.allowedInPage(scheme)) {
                browser.onExternalScheme(this@Tab, u, req.hasUserGesture)
                return GeckoResult.deny()
            }
            if (AddonPolicy.isXpiUrl(u)) {
                app.addons.installFromAmo(AddonPolicy.slugFromListing(req.triggerUri) ?: AddonPolicy.slugFromListing(url.value) ?: Uri.parse(u).lastPathSegment ?: u)
                return GeckoResult.deny()
            }
            if (u.endsWith(".user.js") && (scheme == "https" || scheme == "http")) { browser.offerUserscript(u); return GeckoResult.deny() }
            val host = try { Uri.parse(u).host } catch (_: Exception) { null }
            if (host != null) {
                val hit = if (host in phishingBypass) null else app.threats.match(u)
                if (hit != null) {
                    warning.value = Warning.Phishing(u, host, hit.rule)
                    return GeckoResult.deny()
                }
                return learnThenAllow(host)
            }
            return GeckoResult.allow()
        }

        override fun onSubframeLoadRequest(session: GeckoSession, req: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val host = try { Uri.parse(req.uri).host } catch (_: Exception) { null } ?: return GeckoResult.allow()
            if (app.threats.match(req.uri) != null && host !in phishingBypass) return GeckoResult.deny()
            return learnThenAllow(host)
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            val t = browser.newTabForPopup(this@Tab)
            return GeckoResult.fromValue(t.session)
        }
    }

    /** New exact payment host → push it into Gecko's quarantine / restricted sets before the document loads. */
    private fun learnThenAllow(host: String): GeckoResult<AllowOrDeny> {
        if (!(payment || app.payments.isPayment(host) || PaymentDomains.looksLikeAcs(host))) return GeckoResult.allow()
        if (!app.engine.learnPaymentHost(host)) return GeckoResult.allow()
        val r = GeckoResult<AllowOrDeny>()
        app.engine.afterPrefsApplied { r.complete(AllowOrDeny.ALLOW) }
        return r
    }

    private inner class Progress : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, u: String) { loading.value = true; progress.floatValue = 0.05f; if (!incognito) app.markDirty() }
        override fun onPageStop(session: GeckoSession, success: Boolean) { loading.value = false; progress.floatValue = 1f }
        override fun onProgressChange(session: GeckoSession, p: Int) { progress.floatValue = p / 100f }
        override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) { secure.value = info.isSecure }
    }

    private inner class Content : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, t: String?) { title.value = t ?: "" }
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { browser.fullscreen.value = fullScreen }
        override fun onCloseRequest(session: GeckoSession) { browser.closeTab(this@Tab) }
        override fun onContextMenu(session: GeckoSession, x: Int, y: Int, e: GeckoSession.ContentDelegate.ContextElement) { browser.contextMenu(this@Tab, e) }
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) { browser.downloads.offer(this@Tab, response) }
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
