package org.ivanivorontsov.tiho.browser

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.ext.ScreenSize
import org.ivanivorontsov.tiho.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ScreenSizeTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as TihoApp

    private fun <T> onMain(block: () -> T): T { var r: T? = null; ins.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun serve(html: String): Pair<ServerSocket, Int> {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!ss.isClosed) try {
                ss.accept().use { s ->
                    val r = s.getInputStream().bufferedReader(); while (r.readLine()?.isNotEmpty() == true) Unit
                    val body = html.toByteArray()
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                }
            } catch (_: Exception) {}
        }
        return ss to ss.localPort
    }

    private fun probe(spoof: Boolean): String {
        val html = "<meta name=viewport content='width=device-width'><script>" +
            "function r(){document.title='S'+screen.width+'x'+screen.height+'x'+screen.colorDepth+'|A'+screen.availWidth+'x'+screen.availHeight+'|O'+outerWidth+'x'+outerHeight+'|I'+innerWidth+'x'+innerHeight}" +
            "r();addEventListener('load',r)</script><body>t</body>"
        val (ss, port) = serve(html)
        ss.use {
            onMain { org.ivanivorontsov.tiho.dist.Dist.setScreenSpoof(app, spoof) }
            ActivityScenario.launch(MainActivity::class.java).use {
                val end0 = SystemClock.uptimeMillis() + 300_000
                while (SystemClock.uptimeMillis() < end0 && onMain { app.addons.tihoExt } == null) SystemClock.sleep(250)
                SystemClock.sleep(10_000)
                onMain { app.browser!!.navigate("http://127.0.0.1:$port/") }
                var title = ""
                val end = SystemClock.uptimeMillis() + 480_000
                var again = SystemClock.uptimeMillis() + 90_000
                while (SystemClock.uptimeMillis() < end) {
                    if (SystemClock.uptimeMillis() > again) { again += 90_000; onMain { app.browser!!.navigate("http://127.0.0.1:$port/") } }
                    title = onMain { app.browser!!.current.value?.title?.value ?: "" }
                    if (title.startsWith("S")) break
                    SystemClock.sleep(250)
                }
                android.util.Log.i("TihoScreenTest", title)
                assertTrue("page did not report: '$title'", title.startsWith("S"))
                return title
            }
        }
    }

    private fun parts(t: String) = t.split("|").associate { it.take(1) to it.drop(1).split("x").map(String::toInt) }

    @Test fun spoofOnReportsCommonSize() {
        val t = probe(true)
        val p = parts(t)
        val i = p["I"]!!
        val c = ScreenSize.pick(i[0], i[1])!!
        android.util.Log.i("TihoScreenTest", "expected ${c.first}x${c.second}")
        assertEquals(t, listOf(c.first, c.second, 24), p["S"])
        assertEquals(t, listOf(c.first, c.second), p["A"])
        assertEquals(t, listOf(c.first, c.second), p["O"])
        assertTrue(t, i[0] <= c.first && i[1] <= c.second)
        if (i[0] <= ScreenSize.WIDTH && i[1] <= ScreenSize.HEIGHT) assertEquals(t, listOf(ScreenSize.WIDTH, ScreenSize.HEIGHT, 24), p["S"])
    }

    @Test fun spoofOffReportsRfpWindowSize() {
        val t = probe(false)
        val p = parts(t)
        assertEquals(t, 24, p["S"]!![2])
        assertEquals(t, p["I"], p["S"]!!.take(2))
        assertEquals(t, p["I"], p["A"])
        assertEquals(t, p["I"], p["O"])
    }
}
