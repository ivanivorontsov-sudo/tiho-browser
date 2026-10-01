package org.ivanivorontsov.tiho.browser

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the 2.0.0 crash: following a target=_blank / window.open link killed the app with
 * AssertionError "Must use an unopened GeckoSession instance" (onNewSession returned an already opened session).
 */
@RunWith(AndroidJUnit4::class)
class PopupNavigationTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as TihoApp

    private fun <T> onMain(block: () -> T): T { var r: T? = null; ins.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) { if (onMain(cond)) return true; SystemClock.sleep(250) }
        return false
    }

    private fun tapCenter() {
        val gv = onMain { app.browser!!.geckoView!! }
        val loc = IntArray(2); onMain { gv.getLocationOnScreen(loc) }
        val x = loc[0] + gv.width / 2f; val y = loc[1] + gv.height / 2f
        val t = SystemClock.uptimeMillis()
        ins.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        ins.sendPointerSync(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun page(body: String) = "data:text/html;charset=utf-8," + Uri.encode("<meta name=viewport content='width=device-width'><body style='margin:0'>$body</body>")

    @Test fun popupTabSessionIsHandedToGeckoUnopened() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onMain {
                val b = app.browser!!
                val opener = b.current.value ?: b.newTab()
                val popup = b.newTabForPopup(opener)
                assertFalse("Gecko must receive an unopened session", popup.session.isOpen)
                assertEquals(opener.incognito, popup.incognito)
                assertEquals(opener.payment, popup.payment)
                popup.session.open(app.engine.runtime)          // what GeckoView does with the returned session
                assertTrue(popup.session.isOpen)
                b.closeTab(popup)
                val ext = app.addons.onNewTabForGecko!!.invoke()!!
                assertFalse("tabs.create session must be unopened", ext.isOpen)
                b.closeTab(b.tabs.first { it.session === ext })
            }
        }
    }

    private fun clickOpensNewTab(body: String, expectHost: String) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val opener = onMain { app.browser!!.newTab(null) }
            onMain { opener.load(page(body)) }
            assertTrue("test page loaded", waitFor(60_000) { opener.url.value.startsWith("data:") && !opener.loading.value })
            SystemClock.sleep(1500)
            val before = onMain { app.browser!!.tabs.size }
            tapCenter()
            assertTrue("a new tab was opened", waitFor(60_000) { app.browser!!.tabs.size > before })
            val popup = onMain { app.browser!!.tabs.last() }
            assertTrue("Gecko opened the popup session", waitFor(60_000) { popup.session.isOpen })
            assertTrue("popup navigated to $expectHost", waitFor(120_000) { NavPolicy.hostOf(popup.url.value) == expectHost })
            onMain { app.browser!!.closeTab(popup); app.browser!!.closeTab(opener) }
        }
    }

    @Test fun targetBlankLinkOpensTabWithoutCrash() =
        clickOpensNewTab("<a target=_blank href='https://example.com/' style='display:block;width:100vw;height:100vh'>link</a>", "example.com")

    @Test fun windowOpenOnClickOpensTabWithoutCrash() =
        clickOpensNewTab("<div onclick=\"window.open('https://example.org/')\" style='width:100vw;height:100vh'>open</div>", "example.org")
}
