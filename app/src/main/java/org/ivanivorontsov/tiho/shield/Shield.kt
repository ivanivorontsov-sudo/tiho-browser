package org.ivanivorontsov.tiho.shield

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.ivanivorontsov.tiho.TihoApp
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * In-browser ТихоЩит: a built-in WebExtension intercepts requests (Gecko webRequest,
 * the counterpart of WebView shouldInterceptRequest) using the same domain lists as the
 * standalone VPN app. No VpnService and no proxy are created here.
 */
class Shield(private val app: TihoApp) {
    data class Hit(val host: String, val rule: String, val list: String, val time: Long)

    val blocked = mutableStateOf(app.prefs.shieldBlocked)
    val merged = mutableStateOf(0)
    val last = mutableStateListOf<Hit>()
    val sizes = mutableStateMapOf<String, Int>()
    val error = mutableStateOf<String?>(null)
    @Volatile private var port: WebExtension.Port? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(ext: WebExtension) {
        ext.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(p: WebExtension.Port) {
                port = p
                p.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val m = message as? JSONObject ?: return
                        when (m.optString("type")) {
                            "hello", "ready" -> {
                                if (m.has("sizes")) {
                                    val s = m.getJSONObject("sizes")
                                    for (k in s.keys()) sizes[k] = s.optInt(k)
                                }
                                if (m.has("merged")) merged.value = m.optInt("merged")
                                if (m.has("blocked")) applyStats(m)
                            }
                            "stats" -> applyStats(m)
                            "error" -> { error.value = m.optString("message"); Log.e("Tiho", "тиховщит: ${m.optString("message")}") }
                        }
                    }
                    override fun onDisconnect(port: WebExtension.Port) { if (this@Shield.port === port) this@Shield.port = null }
                })
                pushConfig()
            }
        }, "tihoshield")
        pushConfig()
    }

    fun enabled(): Boolean = app.prefs.shieldEnabled
    fun listEnabled(): Map<String, Boolean> = ShieldLists.parseEnabled(app.prefs.shieldLists)

    fun setEnabled(on: Boolean) { app.prefs.shieldEnabled = on; pushConfig() }

    fun setList(id: String, on: Boolean) {
        val cur = listEnabled().toMutableMap()
        cur[id] = on
        app.prefs.shieldLists = ShieldLists.serializeEnabled(cur)
        pushConfig()
    }

    fun resetStats() {
        app.prefs.shieldBlocked = 0
        blocked.value = 0
        last.clear()
        port?.postMessage(JSONObject().put("type", "reset"))
    }

    fun pushConfig() {
        val p = port ?: return
        val lists = JSONObject()
        for ((id, on) in listEnabled()) lists.put(id, on)
        try {
            p.postMessage(JSONObject().put("type", "config").put("enabled", enabled()).put("lists", lists))
        } catch (e: Exception) { Log.e("Tiho", "shield config", e) }
    }

    private fun applyStats(m: JSONObject) {
        val n = m.optInt("blocked")
        blocked.value = n
        if (n > app.prefs.shieldBlocked) app.prefs.shieldBlocked = n
        val arr = m.optJSONArray("last") ?: JSONArray()
        last.clear()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            last += Hit(o.optString("host"), o.optString("rule"), o.optString("list"), o.optLong("t"))
        }
    }
}
