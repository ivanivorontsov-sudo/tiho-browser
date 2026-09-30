package org.ivanivorontsov.tiho.adblock

import org.ivanivorontsov.tiho.adblock.AdblockEngine.Decision
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs the real Rust engine (host build of rust/tihoadblock) through the same JNI surface as on Android. */
class AdblockEngineTest {
    private val rules = """
        ||doubleclick.net^
        ||mc.yandex.ru^${'$'}third-party
        ||ads.example.com^${'$'}script
        @@||ads.example.com/allowed.js
        ||tracker.example^${'$'}important
        example.org##.ad-banner
        ##.global-ad
        example.org#@#.global-ad
        ||cdn.example.net/prebid.js${'$'}script,redirect=noopjs
        ||shop.example^${'$'}removeparam=aff
    """.trimIndent()

    private fun engine(res: String? = null) = AdblockEngine.fromLists(listOf(rules to true), res)

    @Test fun blocksNetworkRules() = engine().use { e ->
        assertEquals(Decision.Block, e.check("https://ad.doubleclick.net/x.js", "https://news.example/", "script"))
        assertEquals(Decision.Allow, e.check("https://news.example/app.js", "https://news.example/", "script"))
    }

    @Test fun thirdPartyOption() = engine().use { e ->
        assertEquals(Decision.Block, e.check("https://mc.yandex.ru/metrika/tag.js", "https://lenta.ru/", "script"))
        assertEquals(Decision.Allow, e.check("https://mc.yandex.ru/metrika/tag.js", "https://yandex.ru/", "script"))
    }

    @Test fun typeOptionsAndExceptions() = engine().use { e ->
        assertEquals(Decision.Block, e.check("https://ads.example.com/a.js", "https://site.test/", "script"))
        assertEquals(Decision.Allow, e.check("https://ads.example.com/a.png", "https://site.test/", "image"))
        assertEquals(Decision.Exception, e.check("https://ads.example.com/allowed.js", "https://site.test/", "script"))
    }

    @Test fun checkAnyBlocksIfAnyTypeMatches() = engine().use { e ->
        assertEquals(Decision.Block, e.checkAny("https://ads.example.com/unknown", "https://site.test/", listOf("image", "script")))
    }

    @Test fun cosmeticFilters() = engine().use { e ->
        val j = JSONObject(e.cosmeticJson("https://example.org/page")!!)
        val hide = j.getJSONArray("hide").toString()
        assertTrue(hide.contains(".ad-banner"))
        val other = JSONObject(e.cosmeticJson("https://other.test/")!!)
        assertFalse(other.getJSONArray("hide").toString().contains(".ad-banner"))
        // generic .global-ad is excepted on example.org
        assertTrue(j.getJSONArray("exc").toString().contains(".global-ad"))
        val sel = e.hiddenSelectors(arrayOf("global-ad", "content"), emptyArray(), emptyArray())
        assertEquals(listOf(".global-ad"), sel)
    }

    @Test fun redirectResources() {
        val res = """[{"name":"noopjs","aliases":["noop.js"],"kind":{"mime":"application/javascript"},"content":"KGZ1bmN0aW9uKCl7fSkoKTs="}]"""
        engine(res).use { e ->
            val d = e.check("https://cdn.example.net/prebid.js", "https://site.test/", "script")
            assertTrue("got $d", d is Decision.Redirect && d.dataUrl.startsWith("data:application/javascript"))
        }
    }

    @Test fun removeparamRewrite() = engine().use { e ->
        val d = e.check("https://shop.example/item?id=5&aff=abc", "https://shop.example/", "document")
        assertTrue("got $d", d is Decision.Rewrite && !(d as Decision.Rewrite).url.contains("aff="))
    }

    @Test fun serializeRoundTrip() = engine().use { e ->
        val bytes = e.serialize()!!
        assertTrue(bytes.size > 100)
        val ptr = AdblockNative.deserialize(bytes)
        assertNotEquals(0L, ptr)
        assertEquals("B", AdblockNative.check(ptr, "https://doubleclick.net/", "https://a.test/", "image", "GET"))
        AdblockNative.free(ptr)
    }

    @Test fun realBundledListsBlockCommonTrackers() {
        val dir = File(System.getProperty("tiho.assets") ?: return, "filters")
        val lists = listOf("easylist", "easyprivacy", "ruadlist").map { File(dir, "$it.txt").readText() to true }
        AdblockEngine.fromLists(lists, null).use { e ->
            val src = "https://lenta.ru/"
            for (u in listOf(
                "https://www.google-analytics.com/analytics.js",
                "https://mc.yandex.ru/metrika/tag.js",
                "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
                "https://an.yandex.ru/system/context.js",
            )) assertEquals(u, Decision.Block, e.checkAny(u, src, listOf("script")))
            assertEquals(Decision.Allow, e.check("https://lenta.ru/static/app.js", src, "script"))
        }
    }
}
