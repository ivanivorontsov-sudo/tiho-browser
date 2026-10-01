package org.ivanivorontsov.tiho.browser

import org.junit.Assert.*
import org.junit.Test

class MainLoopGuardTest {
    private fun at(cls: String, e: Throwable): Throwable = e.apply { stackTrace = arrayOf(StackTraceElement(cls, "m", "F.java", 1)) }

    @Test fun geckoAssertionFromOnNewSessionIsRecoverable() {
        // Exactly the 2.0.0 crash: AssertionError("Must use an unopened GeckoSession instance") wrapped twice.
        val root = at("org.mozilla.geckoview.GeckoSession\$4", AssertionError("Must use an unopened GeckoSession instance"))
        val wrapped = at("org.mozilla.gecko.util.EventCallback", RuntimeException(root))
        assertTrue(MainLoopGuard.isRecoverable(RuntimeException(wrapped)))
    }

    @Test fun appAndSystemFailuresStillCrash() {
        assertFalse(MainLoopGuard.isRecoverable(at("androidx.compose.runtime.Recomposer", IllegalStateException("x"))))
        assertFalse(MainLoopGuard.isRecoverable(at("org.ivanivorontsov.tiho.ui.MainActivity", NullPointerException())))
        assertFalse(MainLoopGuard.isRecoverable(at("org.mozilla.geckoview.GeckoSession", OutOfMemoryError())))
        assertFalse(MainLoopGuard.isRecoverable(RuntimeException(at("org.mozilla.geckoview.X", StackOverflowError()))))
    }

    @Test fun selfCausedChainTerminates() {
        val e = at("a.B", RuntimeException("loop"))
        assertFalse(MainLoopGuard.isRecoverable(e))
    }
}
