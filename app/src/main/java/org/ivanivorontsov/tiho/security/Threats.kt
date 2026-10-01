package org.ivanivorontsov.tiho.security

import android.util.Log
import org.ivanivorontsov.tiho.TihoApp
import java.util.concurrent.Executors

/** Phishing / malware URL lists (bundled, replaced by signed content packs). Checked before every top-level load. */
class Threats(private val app: TihoApp) {
    @Volatile var list: ThreatList = ThreatList.EMPTY
        private set
    private val exec = Executors.newSingleThreadExecutor()

    fun rebuild() = exec.execute {
        if (!app.prefs.phishing) { list = ThreatList.EMPTY; return@execute }
        try {
            val texts = listOf("phishing-filter", "urlhaus").map { n ->
                app.contentPacks.file("security/$n.txt")?.readText()
                    ?: app.assets.open("security/$n.txt").bufferedReader().use { it.readText() }
            }
            list = ThreatList.parse(*texts.toTypedArray())
            Log.i("Tiho", "threat lists: ${list.size} entries")
        } catch (t: Throwable) { Log.e("Tiho", "threat lists failed", t) }
    }

    fun match(url: String): ThreatList.Hit? = if (app.prefs.phishing) list.match(url) else null
}
