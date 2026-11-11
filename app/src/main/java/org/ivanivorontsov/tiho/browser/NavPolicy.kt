package org.ivanivorontsov.tiho.browser

import org.ivanivorontsov.tiho.gecko.AddonPolicy
import org.ivanivorontsov.tiho.privacy.UrlCleaner
import org.ivanivorontsov.tiho.security.SchemePolicy

/**
 * Pure decision logic for top-level and subframe load requests (Gecko NavigationDelegate.onLoadRequest).
 * Kept free of Android/Gecko types so every navigation path is unit-tested; Tab only executes the decision.
 * Never throws: any unexpected input yields [Decision.Allow] (a navigation must never take the app down).
 */
object NavPolicy {
    sealed class Decision {
        data object Allow : Decision()
        /** Scheme not loadable in a page: hand to the external-app flow (or block). */
        data class External(val url: String) : Decision()
        data class InstallXpi(val slugOrUrl: String) : Decision()
        data class OfferUserscript(val url: String) : Decision()
        data class OfferUserstyle(val url: String) : Decision()
        data class OfferWebExt(val url: String) : Decision()
        data class Phishing(val url: String, val host: String, val rule: String) : Decision()
        /** Known redirect wrapper (vk.com/away.php, google.com/url, …): load the real destination directly. */
        data class Unwrap(val url: String) : Decision()
        /** http(s) page; host may need to be pushed into the payment quarantine first. */
        data class Http(val host: String) : Decision()
    }

    data class Request(
        val uri: String?,
        val triggerUri: String? = null,
        val currentUrl: String? = null,
        val topLevel: Boolean = true,
        /** Gecko TARGET_WINDOW_CURRENT (false for _blank: that goes through onNewSession). */
        val sameWindow: Boolean = true,
        val hasUserGesture: Boolean = false,
        val safePayment: Boolean = false,
    )

    private val HOST_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/?#]*@)?([^:/?#\\[\\]]+)")

    fun hostOf(u: String?): String? {
        if (u.isNullOrEmpty()) return null
        val h = try { java.net.URI(u).host } catch (_: Exception) { null }
            // URIs Java rejects ("|" in the query) or IDN hosts it leaves null: take the authority by hand.
            ?: HOST_RE.find(u)?.groupValues?.get(1)
        return h?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }

    fun decide(r: Request, threat: (String) -> String?, phishingBypass: Set<String> = emptySet()): Decision = try {
        decideUnsafe(r, threat, phishingBypass)
    } catch (_: Throwable) { Decision.Allow }

    private fun decideUnsafe(r: Request, threat: (String) -> String?, bypass: Set<String>): Decision {
        val u = r.uri ?: return Decision.Allow
        if (u.isEmpty()) return Decision.Allow
        val scheme = SchemePolicy.schemeOf(u) ?: return Decision.Allow
        if (scheme == "moz-extension" || scheme == "about" || scheme == "resource" && u.startsWith("resource://android/assets/tihoext/")) return Decision.Allow
        if (!SchemePolicy.allowedInPage(scheme)) return Decision.External(u)
        if (scheme != "http" && scheme != "https") return Decision.Allow   // data:, blob: inside pages
        if (r.topLevel && AddonPolicy.isXpiUrl(u))
            return Decision.InstallXpi(AddonPolicy.slugFromListing(r.triggerUri) ?: AddonPolicy.slugFromListing(r.currentUrl) ?: u.substringAfterLast('/'))
        val path = u.substringBefore('?').substringBefore('#')
        if (r.topLevel && path.endsWith(".user.js", ignoreCase = true)) return Decision.OfferUserscript(u)
        if (r.topLevel && path.endsWith(".user.css", ignoreCase = true)) return Decision.OfferUserstyle(u)
        if (r.topLevel && (path.endsWith(".crx", ignoreCase = true) || (path.endsWith(".xpi", ignoreCase = true) && !AddonPolicy.isXpiUrl(u))))
            return Decision.OfferWebExt(u)
        val host = hostOf(u) ?: return Decision.Allow
        if (host !in bypass) threat(u)?.let { return Decision.Phishing(u, host, it) }
        if (r.topLevel && r.sameWindow && !r.safePayment) {
            val dest = UrlCleaner.unwrapOnce(u)
            if (dest != null && dest != u && hostOf(dest) != null && hostOf(dest) != host) {
                // The destination is checked again when it is loaded (phishing list, payment host, scheme).
                return Decision.Unwrap(dest)
            }
        }
        return Decision.Http(host)
    }

    /** window.open / target=_blank: same privacy context (private / «Банк» cookie jar) as the opener. */
    data class PopupContext(val incognito: Boolean, val payment: Boolean)
    fun popupContext(openerIncognito: Boolean, openerPayment: Boolean) = PopupContext(openerIncognito, openerPayment)

    /** Gecko query-stripping list (exact parameter names, space separated). */
    fun queryStripList(): String = UrlCleaner.GLOBAL.sorted().joinToString(" ")
}
