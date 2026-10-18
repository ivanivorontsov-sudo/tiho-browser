package org.ivanivorontsov.tiho.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSizeJsTest {
    @Test fun picksSmallestCommonSizeThatFitsWindow() {
        assertEquals(360 to 800, ScreenSize.pick(360, 697))
        assertEquals(412 to 915, ScreenSize.pick(396, 747))
        assertEquals(393 to 873, ScreenSize.pick(393, 780))
        assertNull(ScreenSize.pick(800, 360))
    }

    @Test fun scriptCarriesTable() {
        val js = ScreenSize.js()
        assertTrue(js.contains("[360,800],[384,854],[393,873],[412,915],[430,932]"))
        assertTrue(js.contains("exportFunction"))
    }
}
