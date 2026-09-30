package org.ivanivorontsov.tiho.adblock

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestTypesTest {
    @Test fun guesses() {
        assertEquals(listOf("document"), RequestTypes.guess("https://a.test/", true, null))
        assertEquals(listOf("sub_frame"), RequestTypes.guess("https://a.test/frame", false, "text/html,application/xhtml+xml"))
        assertEquals(listOf("image"), RequestTypes.guess("https://a.test/x", false, "image/avif,image/webp"))
        assertEquals(listOf("script"), RequestTypes.guess("https://a.test/app.js?v=1", false, "*/*"))
        assertEquals(listOf("stylesheet"), RequestTypes.guess("https://a.test/s.css", false, null))
        assertEquals(listOf("font"), RequestTypes.guess("https://a.test/f.woff2", false, null))
        assertEquals(listOf("media"), RequestTypes.guess("https://a.test/v.m3u8", false, null))
        assertEquals(listOf("websocket"), RequestTypes.guess("wss://a.test/ws", false, null))
        assertEquals(listOf("script", "xmlhttprequest", "image"), RequestTypes.guess("https://a.test/collect", false, "*/*"))
    }
}
