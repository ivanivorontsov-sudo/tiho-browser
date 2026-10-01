package org.ivanivorontsov.tiho.update

import android.util.Log
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.security.Net
import org.ivanivorontsov.tiho.security.PaymentDomains
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Android side of signed content packs: startup verification, applying data, scheduled/manual updates. */
class ContentPacks(private val app: TihoApp) {
    private val prefs get() = app.prefs
    val store = ContentPackStore(File(app.filesDir, "content"))
    private val verifier = ContentPackVerifier(TrustedKeys.keys, BuildConfig.VERSION_CODE)
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var manifest: PackManifest? = null
        private set
    @Volatile var busy = false
        private set

    val version: Long get() = manifest?.version ?: 0L

    fun file(path: String): File? = if (manifest == null) null else store.file(path)
    fun text(path: String): String? = try { file(path)?.readText() } catch (_: Exception) { null }

    /** Re-verifies the installed pack's signature at every start (internal storage is trusted, but cheaply checked). */
    fun init() {
        manifest = try {
            val bytes = File(store.current, "manifest.json").readBytes()
            val sig = File(store.current, "manifest.json.minisig").readText()
            Minisign.verify(bytes, sig, TrustedKeys.keys)
            PackManifest.parse(String(bytes, Charsets.UTF_8))
        } catch (e: java.io.FileNotFoundException) { null } catch (e: Exception) {
            Log.w("Tiho", "installed content pack rejected: ${e.message}")
            store.current.deleteRecursively(); null
        }
        applyData()
    }

    private fun applyData() {
        val m = manifest ?: return
        text("payment-domains.json")?.let { j ->
            try { prefs.paymentKnown = PaymentDomains.merge(emptySet(), prefs.paymentKnown, PaymentDomains.parsePack(j)) } catch (_: Exception) {}
        }
        app.payments.refresh()
    }

    /** One update attempt. Runs on a background thread; [done] gets a human-readable status. */
    fun check(manual: Boolean, done: ((String) -> Unit)? = null) = exec.execute {
        busy = true
        val updater = ContentPackUpdater(store, verifier)
        val r = updater.update(
            highWater = maxOf(prefs.contentHighWater, version),
            allowCode = false, // 2.0 (GeckoView): pack JS shims and filter lists are not used; uBlock Origin manages its own lists
            want = { f -> f.path.startsWith("security/") || f.path.startsWith("userscripts/") || f.path == "payment-domains.json" },
            fetchText = { Net.get(TrustedKeys.CONTENT_URL + it, 1024 * 1024) },
            fetchFile = { f -> Net.get(TrustedKeys.CONTENT_URL + f.url, f.size + 1) },
            validate = { reload() },
            onAccepted = { v -> prefs.contentHighWater = v },
        )
        val status = when (r) {
            is ContentPackUpdater.Result.Installed -> "Установлен пакет v${r.version}"
            is ContentPackUpdater.Result.UpToDate -> "Актуальная версия (v${version})"
            is ContentPackUpdater.Result.RolledBack -> r.reason
            is ContentPackUpdater.Result.Failed -> "Ошибка: ${r.reason}"
        }
        prefs.contentStatus = status
        prefs.contentLastCheck = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())
        Log.i("Tiho", "content pack check (manual=$manual): $status")
        busy = false
        done?.invoke(status)
    }

    /** Loads the currently swapped-in pack and validates it; false triggers rollback. */
    private fun reload(): Boolean {
        init()
        app.threats.rebuild()
        app.engine.applyDynamicPrefs()
        return true
    }
}
