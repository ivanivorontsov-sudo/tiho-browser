package org.ivanivorontsov.tiho.browser

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Last line of defence: an exception thrown on the main thread from inside GeckoView's result/event plumbing
 * (e.g. an AssertionError raised while Gecko consumes a GeckoResult we returned from a delegate) would otherwise kill
 * the whole browser. Such failures concern one navigation / one popup, so we log them, tell the user and keep the
 * main looper running. Anything else (OOM, our UI, Compose, the system) still crashes as usual.
 */
object MainLoopGuard {
    private const val GECKO_UNCAUGHT = "org.mozilla.geckoview.GeckoResult\$UncaughtException"
    private val GECKO_PREFIXES = listOf("org.mozilla.geckoview.", "org.mozilla.gecko.")

    /** Pure: decides from the exception chain alone (unit-tested). */
    fun isRecoverable(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        var gecko = false
        while (c != null && depth < 16) {
            if (c is VirtualMachineError) return false            // OOM / StackOverflow: never swallow
            if (c.javaClass.name == GECKO_UNCAUGHT) gecko = true
            val top = c.stackTrace.firstOrNull()?.className
            if (top != null && GECKO_PREFIXES.any { top.startsWith(it) }) gecko = true
            c = c.cause.takeIf { it !== c }
            depth++
        }
        return gecko
    }

    @Volatile private var installed = false

    fun install(onRecovered: (Throwable) -> Unit) {
        if (installed) return
        installed = true
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post                                     // looper quit: app is going away
                } catch (t: Throwable) {
                    if (!isRecoverable(t)) throw t
                    Log.e("Tiho", "recovered from a GeckoView main-thread failure", t)
                    try { onRecovered(t) } catch (_: Throwable) {}
                }
            }
        }
    }
}
