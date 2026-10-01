package org.ivanivorontsov.tiho.security

import org.junit.Assert.*
import org.junit.Test

class HomographTest {
    @Test fun asciiIsFine() {
        assertFalse(Homograph.check("paypal.com").suspicious)
        assertEquals("example.com", Homograph.display("example.com"))
    }

    @Test fun mixedScripts() {
        val v = Homograph.check("раypal.com") // Cyrillic р, а
        assertTrue(v.suspicious)
        assertTrue(v.ascii.startsWith("xn--"))
        assertEquals(v.ascii, Homograph.display("раypal.com"))
    }

    @Test fun wholeScriptConfusable() {
        assertTrue(Homograph.check("аррӏе.com").suspicious)
        assertTrue(Homograph.check("сосо.com").suspicious)
    }

    @Test fun legitimateCyrillic() {
        assertFalse(Homograph.check("пример.рф").suspicious)
        assertFalse(Homograph.check("госуслуги.рф").suspicious)
        assertFalse(Homograph.check("сосо.рф").suspicious) // Cyrillic TLD: Cyrillic names are expected
        assertEquals("пример.рф", Homograph.display("xn--e1afmkfd.xn--p1ai"))
    }

    @Test fun nullAndEmpty() {
        assertFalse(Homograph.check(null).suspicious)
    }
}
