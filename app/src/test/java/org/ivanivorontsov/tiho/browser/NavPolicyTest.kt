package org.ivanivorontsov.tiho.browser

import org.ivanivorontsov.tiho.browser.NavPolicy.Decision
import org.ivanivorontsov.tiho.browser.NavPolicy.Request
import org.junit.Assert.*
import org.junit.Test

class NavPolicyTest {
    private val none: (String) -> String? = { null }
    private fun d(uri: String?, safe: Boolean = false, same: Boolean = true, top: Boolean = true, threat: (String) -> String? = none, bypass: Set<String> = emptySet()) =
        NavPolicy.decide(Request(uri, currentUrl = "https://yandex.ru/search/?text=x", topLevel = top, sameWindow = same, safePayment = safe), threat, bypass)

    @Test fun ordinaryCrossSiteLinkIsAllowed() {
        assertEquals(Decision.Http("www.mozilla.org"), d("https://www.mozilla.org/ru/firefox/"))
        assertEquals(Decision.Http("example.com"), d("http://Example.COM./a?b=c#d"))
    }

    @Test fun yandexResultRedirectsAreLoadedNormally() {
        // Yandex SERP links go through yandex.ru/clck/jsredir (JS redirect) — must simply load, never crash.
        assertEquals(Decision.Http("yandex.ru"), d("https://yandex.ru/clck/jsredir?from=yandex.ru%3Bsearch&text=&etext=2202.abc&uuid=&state=_BLhILn4SxNIvvL0W45KSic66uCIg23qh8iRG98qeIXme&data=UlNrNmk5WktYejY4cHFySjRXSWhXSnVWUmx&b64e=2&sign=1&keyno=0&cst=AiuY0DBWFJ5Hyx_fyvalFHVo&ref=orjY4mGPRjk5boDnW0uvlrrd71vZw9kp&l10n=ru&cts=1700000000000%40%40events%3D%5B%5D&mc=4.1"))
        // Pipes and other characters java.net.URI rejects still yield the host.
        assertEquals(Decision.Http("yandex.ru"), d("https://yandex.ru/clck/jsredir?a=b|c&d=%%%"))
    }

    @Test fun nullAndEmptyAndGarbageNeverThrow() {
        assertEquals(Decision.Allow, d(null))
        assertEquals(Decision.Allow, d(""))
        assertEquals(Decision.Allow, d("about:blank"))
        assertEquals(Decision.Allow, d("::::"))
        assertEquals(Decision.Allow, d("https://"))
        assertEquals(Decision.Allow, d("moz-extension://ee23/dashboard.html"))
        // A throwing threat matcher must not propagate.
        assertEquals(Decision.Allow, d("https://a.example/", threat = { throw IllegalStateException("boom") }))
    }

    @Test fun nonWebSchemesGoToExternalFlow() {
        assertTrue(d("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end") is Decision.External)
        assertTrue(d("tg://resolve?domain=x") is Decision.External)
        assertTrue(d("javascript:alert(1)") is Decision.External)
        assertTrue(d("file:///sdcard/x.html") is Decision.External)
        assertTrue(d("market://details?id=x", top = false) is Decision.External)
        assertEquals(Decision.Allow, d("data:text/html,hi"))
        assertEquals(Decision.Allow, d("blob:https://a.b/uuid"))
    }

    @Test fun phishingInterstitialAndBypass() {
        val t: (String) -> String? = { if ("evil.example" in it) "||evil.example^" else null }
        assertEquals(Decision.Phishing("https://evil.example/login", "evil.example", "||evil.example^"), d("https://evil.example/login", threat = t))
        assertEquals(Decision.Http("evil.example"), d("https://evil.example/login", threat = t, bypass = setOf("evil.example")))
        // Subframes are checked too.
        assertTrue(d("https://evil.example/frame", top = false, threat = t) is Decision.Phishing)
    }

    @Test fun redirectWrappersAreUnwrappedOnlyOutsidePaymentAndInSameWindow() {
        assertEquals(Decision.Unwrap("https://example.com/page"), d("https://vk.com/away.php?to=https%3A%2F%2Fexample.com%2Fpage"))
        assertEquals(Decision.Unwrap("https://example.com/x"), d("https://www.google.com/url?q=https://example.com/x&sa=U"))
        assertEquals(Decision.Http("vk.com"), d("https://vk.com/away.php?to=https%3A%2F%2Fexample.com%2Fpage", safe = true))
        assertEquals(Decision.Http("vk.com"), d("https://vk.com/away.php?to=https%3A%2F%2Fexample.com%2Fpage", same = false))
        assertEquals(Decision.Http("vk.com"), d("https://vk.com/away.php?to=https%3A%2F%2Fexample.com%2Fpage", top = false))
        // Wrapper pointing to a non-web scheme is not unwrapped.
        assertEquals(Decision.Http("vk.com"), d("https://vk.com/away.php?to=javascript%3Aalert(1)"))
    }

    @Test fun addonsAndUserscripts() {
        assertTrue(d("https://addons.mozilla.org/firefox/downloads/file/123/darkreader-4.9.xpi") is Decision.InstallXpi)
        assertEquals(Decision.OfferUserscript("https://greasyfork.org/scripts/1/code/x.user.js"), d("https://greasyfork.org/scripts/1/code/x.user.js"))
        assertTrue(d("https://evil.example/fake.xpi") is Decision.Http)
    }

    @Test fun idnHostsReachHomographCheck() {
        assertEquals(Decision.Http("xn--80ak6aa92e.com"), d("https://xn--80ak6aa92e.com/"))
        assertTrue(d("https://аррӏе.com/") is Decision.Http)
    }

    @Test fun popupsKeepOpenerContext() {
        assertEquals(NavPolicy.PopupContext(incognito = true, payment = false), NavPolicy.popupContext(true, false))
        assertEquals(NavPolicy.PopupContext(incognito = false, payment = true), NavPolicy.popupContext(false, true))
    }

    @Test fun stripListContainsYandexAndGoogleClickIds() {
        val l = NavPolicy.queryStripList().split(' ')
        assertTrue("yclid" in l && "gclid" in l && "fbclid" in l)
        assertTrue(l.none { it.isBlank() || it.contains(' ') })
    }
}
