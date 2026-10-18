package org.ivanivorontsov.tiho.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarScrollTest {
    @Test fun hidesOnScrollDownShowsOnScrollUp() {
        assertEquals(-40f, ToolbarScroll.next(0f, 140, 100, 150f))
        assertEquals(-150f, ToolbarScroll.next(-40f, 1000, 140, 150f))
        assertEquals(-100f, ToolbarScroll.next(-150f, 950, 1000, 150f))
        assertEquals(0f, ToolbarScroll.next(-100f, 500, 950, 150f))
    }

    @Test fun visibleAtTopOrWithoutHeight() {
        assertEquals(0f, ToolbarScroll.next(-150f, 0, 300, 150f))
        assertEquals(0f, ToolbarScroll.next(0f, 900, 100, 0f))
    }
}
