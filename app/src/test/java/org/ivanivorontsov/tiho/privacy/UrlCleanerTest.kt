package org.ivanivorontsov.tiho.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlCleanerTest {
    @Test fun stripsGlobalAndPrefixParams() {
        assertEquals("https://a.test/p?id=1", UrlCleaner.clean("https://a.test/p?utm_source=x&id=1&fbclid=abc&yclid=9"))
        assertEquals("https://a.test/p", UrlCleaner.clean("https://a.test/p?utm_medium=a&gclid=b"))
        assertEquals("https://a.test/p?q=1#frag", UrlCleaner.clean("https://a.test/p?q=1&ysclid=zz#frag"))
        assertEquals("https://a.test/p?x=1", UrlCleaner.clean("https://a.test/p?x=1"))
    }

    @Test fun perSiteParams() {
        assertEquals("https://youtu.be/abc", UrlCleaner.clean("https://youtu.be/abc?si=TRACK"))
        assertEquals("https://example.com/?si=keep", UrlCleaner.clean("https://example.com/?si=keep"))
    }

    @Test fun unwrapsRedirectors() {
        assertEquals("https://target.test/a", UrlCleaner.clean("https://www.google.com/url?q=https%3A%2F%2Ftarget.test%2Fa%3Futm_source%3Dg&sa=D"))
        assertEquals("https://target.test/", UrlCleaner.clean("https://vk.com/away.php?to=https%3A%2F%2Ftarget.test%2F&cc_key="))
        assertEquals("https://target.test/x", UrlCleaner.clean("https://l.facebook.com/l.php?u=https%3A%2F%2Ftarget.test%2Fx%3Ffbclid%3D1&h=AT0"))
        assertEquals("https://target.test/", UrlCleaner.clean("https://duckduckgo.com/l/?uddg=https%3A%2F%2Ftarget.test%2F"))
    }

    @Test fun unwrapsAmp() {
        assertEquals("https://news.test/story", UrlCleaner.clean("https://www.google.com/amp/s/news.test/story"))
        assertEquals("https://news.test/story", UrlCleaner.clean("https://news-test.cdn.ampproject.org/c/s/news.test/story"))
    }

    @Test fun respectsSwitches() {
        val u = "https://www.google.com/url?q=https%3A%2F%2Ft.test%2F%3Futm_source%3Dx"
        assertEquals(u, UrlCleaner.clean(u, stripParams = false, unwrap = false))
    }

    @Test fun site() {
        assertEquals("example.com", UrlCleaner.site("a.b.example.com"))
        assertEquals("bbc.co.uk", UrlCleaner.site("www.bbc.co.uk"))
        assertEquals("lenta.ru", UrlCleaner.site("m.lenta.ru"))
        assertEquals("127.0.0.1", UrlCleaner.site("127.0.0.1"))
    }
}
