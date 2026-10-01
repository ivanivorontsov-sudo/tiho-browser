package org.ivanivorontsov.tiho.security

import org.ivanivorontsov.tiho.security.SchemePolicy.Result
import org.ivanivorontsov.tiho.security.SchemePolicy.Source
import org.junit.Assert.*
import org.junit.Test

class SchemePolicyTest {
    @Test fun httpAndHttpsLoad() {
        assertEquals(Result.Load("https://example.com/"), SchemePolicy.check("https://example.com/", Source.EXTERNAL))
        assertEquals(Result.Load("http://example.com"), SchemePolicy.check("  http://example.com ", Source.TYPED))
        assertEquals(Result.Load("HTTPS://EXAMPLE.COM"), SchemePolicy.check("HTTPS://EXAMPLE.COM", Source.EXTERNAL))
    }

    @Test fun dangerousSchemesBlockedEverywhere() {
        for (src in Source.values()) for (u in listOf("javascript:alert(1)", "JavaScript:alert(1)", "file:///sdcard/x.html", "content://com.evil/x",
                "vbscript:x", "jAvAsCrIpT:alert(1)", " javascript:alert(1)", "java\u200bscript:alert(1)", "\u202ejavascript:alert(1)", "view-source:https://a.b")) {
            assertTrue("$u from $src", SchemePolicy.check(u, src) is Result.Blocked)
        }
    }

    @Test fun internalOnlySchemes() {
        assertTrue(SchemePolicy.check("data:text/html,<script>1</script>", Source.EXTERNAL) is Result.Blocked)
        assertTrue(SchemePolicy.check("data:text/html,hi", Source.TYPED) is Result.Blocked)
        assertTrue(SchemePolicy.check("intent://x#Intent;scheme=http;end", Source.EXTERNAL) is Result.Blocked)
        assertTrue(SchemePolicy.check("blob:https://a.b/1", Source.TYPED) is Result.Blocked)
        assertTrue(SchemePolicy.check("data:text/plain,x", Source.INTERNAL) is Result.Load)
    }

    @Test fun hostPortIsNotAScheme() {
        assertEquals(Result.Text("localhost:8080"), SchemePolicy.check("localhost:8080", Source.TYPED))
        assertEquals(Result.Text("example.com:443/x"), SchemePolicy.check("example.com:443/x", Source.TYPED))
        assertEquals(Result.Text("котики"), SchemePolicy.check("котики", Source.TYPED))
    }

    @Test fun aboutPages() {
        assertEquals(Result.Load("about:blank"), SchemePolicy.check("about:blank", Source.EXTERNAL))
        assertTrue(SchemePolicy.check("about:config", Source.TYPED) is Result.Blocked)
    }

    @Test fun otherSchemesBecomeSearchText() {
        assertTrue(SchemePolicy.check("mailto:a@b.c", Source.TYPED) is Result.Text)
        assertTrue(SchemePolicy.check("market://details?id=x", Source.INTERNAL) is Result.Blocked)
    }

    @Test fun sharedText() {
        assertEquals(Result.Load("https://a.example/x?y=1"), SchemePolicy.fromSharedText("Смотри https://a.example/x?y=1 круто"))
        assertEquals(Result.Text("просто текст"), SchemePolicy.fromSharedText("просто текст"))
        assertTrue(SchemePolicy.fromSharedText("javascript:alert(document.cookie)") is Result.Blocked)
        assertTrue(SchemePolicy.fromIntent("file:///data/data/org.ivanivorontsov.tiho/shared_prefs/p.xml") is Result.Blocked)
    }

    @Test fun inPageSchemes() {
        assertTrue(SchemePolicy.allowedInPage("https"))
        assertTrue(SchemePolicy.allowedInPage("blob"))
        assertFalse(SchemePolicy.allowedInPage("file"))
        assertFalse(SchemePolicy.allowedInPage("intent"))
    }
}
