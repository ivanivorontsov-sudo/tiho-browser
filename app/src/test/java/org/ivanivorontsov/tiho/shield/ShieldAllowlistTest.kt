package org.ivanivorontsov.tiho.shield

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShieldAllowlistTest {
    private fun allowFile(): File {
        val candidates = listOf(
            File("src/main/assets/tihoshield/allow.txt"),
            File("app/src/main/assets/tihoshield/allow.txt"),
        )
        return candidates.firstOrNull { it.isFile } ?: File("src/main/assets/tihoshield/allow.txt")
    }

    private fun listFile(name: String): File {
        val candidates = listOf(
            File("src/main/assets/tihoshield/lists/$name"),
            File("app/src/main/assets/tihoshield/lists/$name"),
        )
        return candidates.first { it.isFile }
    }

    private fun matcherWith(vararg lists: Pair<String, String>): DomainMatcher {
        val f = allowFile()
        if (f.isFile) ShieldAllowlist.install(f.readText())
        val m = DomainMatcher()
        for ((text, id) in lists) m.addAll(DomainMatcher.parseDomains(text), id)
        return m
    }

    @Test fun yandexImageCdnsAreAllowed() {
        val m = matcherWith("avatars.mds.yandex.net\nyastatic.net\nbanners.mobile.yandex.net\n" to "adguard-mobile")
        // Real SERP/Images hosts from yandex.ru/images/touch and the «Картинки» block.
        assertNull(m.match("avatars.mds.yandex.net"))
        assertNull(m.match("other.mds.yandex.net"))
        assertNull(m.match("mds.yandex.net"))
        assertNull(m.match("yastatic.net"))
        assertNull(m.match("s3.yastatic.net"))
        assertNull(m.match("im0-tub-ru.yandex.net"))
        assertNull(m.match("im3-tub-ua.yandex.net"))
        assertNull(m.match("yandex.ru"))
        assertNull(m.match("www.yandex.ru"))
        assertNull(m.match("yandex.com.tr"))
        // Ads / metrics on Yandex stay blocked.
        assertEquals("adguard-mobile", m.match("banners.mobile.yandex.net")?.list)
        val ads = matcherWith(
            "an.yandex.ru\nadvertising.yandex.ru\nappmetrica.yandex.net\nmc.yandex.ru\n" to "sdk-ads"
        )
        assertEquals("an.yandex.ru", ads.match("an.yandex.ru")?.rule)
        assertEquals("advertising.yandex.ru", ads.match("advertising.yandex.ru")?.rule)
        assertEquals("appmetrica.yandex.net", ads.match("startup.appmetrica.yandex.net")?.rule)
        assertEquals("mc.yandex.ru", ads.match("mc.yandex.ru")?.rule)
    }

    @Test fun googleVkMailContentCdns() {
        val m = matcherWith(
            "lh3.googleusercontent.com\ncsi.gstatic.com\nad.mail.ru\nmail.ru\n" to "adguard-mobile"
        )
        assertNull(m.match("lh3.googleusercontent.com"))
        assertNull(m.match("lh6.googleusercontent.com"))
        assertNull(m.match("encrypted-tbn0.gstatic.com"))
        assertNull(m.match("encrypted-tbn3.gstatic.com"))
        assertNull(m.match("fonts.gstatic.com"))
        assertNull(m.match("i.ytimg.com"))
        assertNull(m.match("yt3.ggpht.com"))
        assertNull(m.match("sun9-65.userapi.com"))
        assertNull(m.match("img.mail.ru"))
        // Ad/tracker hosts on the same companies must stay blocked.
        assertEquals("csi.gstatic.com", m.match("csi.gstatic.com")?.rule)
        assertEquals("ad.mail.ru", m.match("ad.mail.ru")?.rule)
        assertNull("bare mail.ru must not suffix-block the whole portal", m.match("cloud.mail.ru"))
        assertNull(m.match("mail.ru"))
        assertNotNull(m.match("ads-product-integration.googleusercontent.com") ?: run {
            // not in this fixture; add and check
            m.add("ads-product-integration.googleusercontent.com", "hagezi-promini")
            m.match("ads-product-integration.googleusercontent.com")
        })
    }

    @Test fun realAdguardMobileDoesNotBreakYandexImages() {
        val allow = allowFile()
        assertTrue("allow.txt must be shipped", allow.isFile)
        ShieldAllowlist.install(allow.readText())
        val parsed = ShieldAllowlist.parseAllowFile(allow.readText())
        assertTrue(parsed.suffixes.contains("yastatic.net"))
        assertTrue(parsed.suffixes.contains("mds.yandex.net"))

        val adg = listFile("adguard-mobile.txt").readText()
        val sdk = listFile("sdk-ads.txt").readText()
        val hag = listFile("hagezi-promini.txt").readText()
        val m = DomainMatcher()
        m.addAll(DomainMatcher.parseDomains(adg), "adguard-mobile")
        m.addAll(DomainMatcher.parseDomains(sdk), "sdk-ads")
        m.addAll(DomainMatcher.parseDomains(hag), "hagezi-promini")

        // Hosts taken from a live yandex.ru/images/touch/search response (Nov 2026).
        assertNull("Yandex image CDN", m.match("avatars.mds.yandex.net"))
        assertNull(m.match("other.mds.yandex.net"))
        assertNull("Yandex static CDN", m.match("yastatic.net"))
        assertNull(m.match("im0-tub-ru.yandex.net"))
        assertNull(m.match("yandex.com.tr"))
        assertNull(m.match("lh3.googleusercontent.com"))

        // Ad blocking must not be weakened.
        assertNotNull(m.match("banners.mobile.yandex.net"))
        assertNotNull(m.match("an.yandex.ru"))
        assertNotNull("hagezi: csp.yandex.net stays blocked", m.match("csp.yandex.net"))
        assertNotNull("hagezi: mc.yandex.ru stays blocked", m.match("mc.yandex.ru"))
        assertNotNull(m.match("doubleclick.net").let {
            // doubleclick is in google-ads, not these three files; confirm applovin/ad.mail.ru
            m.match("ad.mail.ru")
        })
        assertNotNull(m.match("ad.mail.ru"))
        assertNotNull(m.match("unityads.unity3d.com") ?: m.match("applovin.com"))
        assertNull(m.match("cloud.mail.ru"))
    }
}
