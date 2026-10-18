package org.ivanivorontsov.tiho.gecko

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.ext.UserScriptJs
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.security.Net
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.util.concurrent.Executors

/**
 * Real WebExtensions (GeckoView): bundled uBlock Origin, installs from addons.mozilla.org with SHA-256 pinning,
 * enable / disable / remove, private-browsing and optional site access per add-on, payment allowlist.
 */
class Addons(private val app: TihoApp) {
    data class Row(
        val ext: WebExtension, val id: String, val name: String, val version: String, val description: String,
        val enabled: Boolean, val appDisabled: Boolean, val private: Boolean, val recommended: Boolean,
        val pin: AddonPolicy.Pin?, val trusted: Boolean, val trustBroken: Boolean,
        val requiredOrigins: List<String>, val optionalOrigins: List<String>, val grantedOrigins: List<String>,
        val permissions: List<String>, val optionsUrl: String?, val signedState: Int,
    )

    data class Prompt(
        val title: String, val text: String, val permissions: List<String>, val origins: List<String>,
        val showPrivate: Boolean, val onResult: (allow: Boolean, private: Boolean) -> Unit,
    )

    val rows = mutableStateListOf<Row>()
    val icons = mutableStateMapOf<String, Bitmap>()
    val actions = mutableStateMapOf<String, WebExtension.Action>()
    val prompt = mutableStateOf<Prompt?>(null)
    val busy = mutableStateOf<String?>(null)
    @Volatile var tihoExt: WebExtension? = null
        private set
    private var tihoPort: WebExtension.Port? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ctl: WebExtensionController
    private val prefs get() = app.prefs
    /** id → installed version, for pin checks (refreshed with [rows]). */
    @Volatile private var installed: Map<String, String> = emptyMap()
    /** Installs we started ourselves (bundled/AMO after our own dialog) → no second Gecko permission prompt. */
    private val preApproved = HashMap<String, Boolean>()
    var onTabRequest: ((String) -> GeckoSession?)? = null
    /** New unopened tab session for Gecko to open (tabs.create). */
    var onNewTabForGecko: (() -> GeckoSession?)? = null
    var onPopup: ((WebExtension, GeckoSession) -> Unit)? = null
    var toast: (String) -> Unit = {}

    fun start() {
        ctl = app.engine.runtime.webExtensionController
        ctl.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(ext: WebExtension, perms: Array<String>, origins: Array<String>, data: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse> {
                preApproved.remove(ext.id)?.let { priv -> return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, priv, false)) }
                val r = GeckoResult<WebExtension.PermissionPromptResponse>()
                prompt.value = Prompt("Установить «${ext.metaData.name ?: ext.id}»?", installText(ext), perms.toList(), origins.toList(), true) { ok, priv ->
                    r.complete(WebExtension.PermissionPromptResponse(ok, priv, false))
                }
                return r
            }
            override fun onUpdatePrompt(ext: WebExtension, perms: Array<String>, origins: Array<String>, data: Array<String>): GeckoResult<AllowOrDeny> =
                ask("Обновление «${ext.metaData.name}» просит новые разрешения", perms, origins)
            override fun onOptionalPrompt(ext: WebExtension, perms: Array<String>, origins: Array<String>, data: Array<String>): GeckoResult<AllowOrDeny> =
                ask("«${ext.metaData.name}» просит дополнительные разрешения", perms, origins)
        }
        ctl.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) { attach(extension); refresh() }
            override fun onUninstalled(extension: WebExtension) { refresh() }
            override fun onEnabled(extension: WebExtension) { attach(extension); refresh() }
            override fun onDisabled(extension: WebExtension) { refresh() }
            override fun onReady(extension: WebExtension) { attach(extension) }
            override fun onOptionalPermissionsChanged(extension: WebExtension) { refresh() }
        })
        ctl.ensureBuiltIn("resource://android/assets/tihoext/", AddonPolicy.TIHO_EXT_ID).accept({ ext ->
            tihoExt = ext ?: return@accept
            ext.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    tihoPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val m = message as? JSONObject ?: return
                            when (m.optString("type")) {
                                "ready" -> pushConfig()
                                "applied" -> Log.i("Tiho", "tihoext applied ${m.optInt("count")} scripts ${m.optJSONArray("errors")}")
                            }
                        }
                        override fun onDisconnect(port: WebExtension.Port) { if (tihoPort === port) tihoPort = null }
                    })
                }
            }, "tiho")
            app.browser?.attachTihoExt(ext)
        }, { e -> Log.e("Tiho", "built-in extension failed", e) })
        // Undo pauses left over from a previous process (no «Банк» tab exists at start).
        resumePaused()
        if (!prefs.uboInstalled) installBundledUbo() else refresh()
    }

    private fun installText(ext: WebExtension): String = buildString {
        val md = ext.metaData
        append("Версия ${md.version}")
        if (md.isRecommended) append(" · рекомендовано Mozilla")
        append(when (md.signedState) { 2, 3, 4 -> " · подпись Mozilla проверена"; else -> " · подпись: ${md.signedState}" })
        if (!md.description.isNullOrBlank()) append("\n\n").append(md.description)
    }

    private fun ask(title: String, perms: Array<String>, origins: Array<String>): GeckoResult<AllowOrDeny> {
        val r = GeckoResult<AllowOrDeny>()
        prompt.value = Prompt(title, "", perms.toList(), origins.toList(), false) { ok, _ -> r.complete(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
        return r
    }

    /** Wires per-extension delegates (popups, new tabs) for any installed extension. */
    private fun attach(ext: WebExtension) {
        if (ext.id == AddonPolicy.TIHO_EXT_ID) return
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) { actions[extension.id] = action }
            override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) { if (!actions.containsKey(extension.id)) actions[extension.id] = action }
            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> = openPopup(extension)
            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> = openPopup(extension)
        })
        ext.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession> {
                return try {
                    val s = onNewTabForGecko?.invoke()?.takeIf { !it.isOpen } ?: return GeckoResult.fromValue(null)
                    GeckoResult.fromValue(s)
                } catch (e: Throwable) {
                    android.util.Log.e("Tiho", "tabs.create", e)
                    details.url?.let { u -> onTabRequest?.invoke(u) }
                    GeckoResult.fromValue(null)
                }
            }
            override fun onOpenOptionsPage(source: WebExtension) { try { source.metaData.optionsPageUrl?.let { onTabRequest?.invoke(it) } } catch (e: Throwable) { android.util.Log.e("Tiho", "options page", e) } }
        })
    }

    private fun openPopup(ext: WebExtension): GeckoResult<GeckoSession> {
        val s = GeckoSession(org.mozilla.geckoview.GeckoSessionSettings.Builder().build())
        s.open(app.engine.runtime)
        onPopup?.invoke(ext, s)
        return GeckoResult.fromValue(s)
    }

    fun refresh() {
        if (!this::ctl.isInitialized) return
        ctl.list().accept({ list ->
            val pins = AddonPolicy.parsePins(prefs.addonPins)
            val trust = AddonPolicy.parseTrust(prefs.addonPaymentTrust)
            val exts = list.orEmpty().filter { !it.isBuiltIn && it.id != AddonPolicy.TIHO_EXT_ID }
            installed = exts.associate { it.id to (it.metaData.version ?: "") }
            val valid = AddonPolicy.validTrusted(trust, pins, installed)
            rows.clear()
            for (e in exts) {
                val md = e.metaData
                rows += Row(e, e.id, md.name ?: e.id, md.version ?: "", md.description ?: "",
                    md.enabled, md.disabledFlags and WebExtension.DisabledFlags.APP != 0, md.allowedInPrivateBrowsing, md.isRecommended,
                    pins[e.id], e.id in valid, e.id in trust && e.id !in valid,
                    md.requiredOrigins.toList(), md.optionalOrigins.toList(), md.grantedOptionalOrigins.toList(),
                    md.requiredPermissions.toList(), md.optionsPageUrl, md.signedState)
                attach(e)
                if (!icons.containsKey(e.id)) md.icon?.getBitmap(96)?.accept({ b -> if (b != null) icons[e.id] = b }, { })
            }
            app.engine.applyDynamicPrefs()
        }, { e -> Log.e("Tiho", "list extensions", e) })
    }

    fun installedIds(): Set<String> = installed.keys

    /** Add-ons allowed on payment pages right now (valid pins) + our built-in one if a userscript is trusted there. */
    fun trustedForPayment(): Set<String> {
        val t = AddonPolicy.validTrusted(AddonPolicy.parseTrust(prefs.addonPaymentTrust), AddonPolicy.parsePins(prefs.addonPins), installed)
        return if (app.extensions.scripts.any { it.enabled && it.runsOnPayment }) t + AddonPolicy.TIHO_EXT_ID else t
    }

    // ------------------------------------------------------------------ install
    private fun recordPin(id: String, version: String, sha: String, src: String) {
        val p = AddonPolicy.parsePins(prefs.addonPins); p[id] = AddonPolicy.Pin(version, sha, src); prefs.addonPins = AddonPolicy.pinsJson(p)
    }

    private fun installBundledUbo() {
        io.execute {
            try {
                val dir = File(app.filesDir, "ext").apply { mkdirs() }
                val f = File(dir, "ublock_origin.xpi")
                app.assets.open("extensions/ublock_origin.xpi").use { i -> f.outputStream().use { i.copyTo(it) } }
                val sha = UserScriptPin.sha256(f.readBytes())
                main.post {
                    preApproved[AddonPolicy.UBO_ID] = true
                    ctl.install("file://" + f.absolutePath, WebExtensionController.INSTALLATION_METHOD_FROM_FILE).accept({ ext ->
                        f.delete()
                        if (ext == null) return@accept
                        recordPin(ext.id, ext.metaData.version ?: "", sha, "bundled")
                        // Default payment allowlist: the bundled, hash-pinned uBlock Origin.
                        val t = AddonPolicy.parseTrust(prefs.addonPaymentTrust)
                        t[ext.id] = AddonPolicy.trustToken(AddonPolicy.Pin(ext.metaData.version ?: "", sha, "bundled"))
                        prefs.addonPaymentTrust = AddonPolicy.trustJson(t)
                        prefs.uboInstalled = true
                        Log.i("Tiho", "bundled uBO ${ext.metaData.version} installed sha256=$sha")
                        refresh()
                    }, { e -> Log.e("Tiho", "bundled uBO install failed", e); refresh() })
                }
            } catch (e: Exception) { Log.e("Tiho", "bundled uBO", e) }
        }
    }

    /** AMO search results (background thread → callback on main). */
    fun search(q: String?, done: (List<AddonPolicy.AmoAddon>?, String?) -> Unit) = io.execute {
        try {
            val r = AddonPolicy.parseSearch(Net.getText(if (q.isNullOrBlank()) AddonPolicy.featuredUrl() else AddonPolicy.searchUrl(q), 4L * 1024 * 1024))
            main.post { done(r, null) }
        } catch (e: Exception) { main.post { done(null, e.message) } }
    }

    /** Installs from AMO: API lookup → HTTPS download → SHA-256 must equal AMO's hash → Gecko install (signature checked by Gecko). */
    fun installFromAmo(idOrSlug: String) {
        busy.value = "Получаю сведения с addons.mozilla.org…"
        io.execute {
            try {
                val a = AddonPolicy.parseAddon(JSONObject(Net.getText(AddonPolicy.addonUrl(idOrSlug), 2L * 1024 * 1024)))
                main.post { confirmAndInstall(a) }
            } catch (e: Exception) { main.post { busy.value = null; toast("Не удалось получить дополнение: ${e.message}") } }
        }
    }

    fun installFromAmo(a: AddonPolicy.AmoAddon) = confirmAndInstall(a)

    private fun confirmAndInstall(a: AddonPolicy.AmoAddon) {
        busy.value = null
        prompt.value = Prompt("Установить «${a.name}» ${a.version}?",
            (if (a.recommended) "Рекомендовано Mozilla. " else "Не входит в программу «Рекомендуемые» Mozilla — устанавливайте только то, чему доверяете. ") +
                "Файл будет загружен с addons.mozilla.org и проверен по SHA-256 ${UserScriptPin.pretty(a.sha256).take(39)}…; подпись Mozilla проверит движок Gecko.\n\n${a.summary}",
            emptyList(), emptyList(), true) { ok, priv -> if (ok) download(a, priv) }
    }

    private fun download(a: AddonPolicy.AmoAddon, private: Boolean) {
        busy.value = "Загрузка ${a.name}…"
        io.execute {
            val dir = File(app.filesDir, "ext").apply { mkdirs() }
            val f = File(dir, "amo-${a.sha256.take(16)}.xpi")
            try {
                require(AddonPolicy.isAmoFileUrl(a.fileUrl))
                val sha = Net.download(a.fileUrl, f, if (a.size > 0) a.size + 1 else 64L * 1024 * 1024)
                if (sha != a.sha256) throw SecurityException("SHA-256 не совпадает с addons.mozilla.org")
                main.post {
                    busy.value = "Установка ${a.name}…"
                    preApproved[a.guid] = private
                    ctl.install("file://" + f.absolutePath, WebExtensionController.INSTALLATION_METHOD_MANAGER).accept({ ext ->
                        f.delete(); busy.value = null
                        if (ext == null) return@accept
                        if (ext.id != a.guid) { toast("Предупреждение: id дополнения ${ext.id} ≠ ${a.guid}") }
                        recordPin(ext.id, ext.metaData.version ?: "", sha, "amo")
                        if (private && !ext.metaData.allowedInPrivateBrowsing) ctl.setAllowedInPrivateBrowsing(ext, true)
                        toast("Установлено: ${ext.metaData.name} ${ext.metaData.version}")
                        refresh()
                    }, { e -> f.delete(); busy.value = null; preApproved.remove(a.guid); toast("Установка не удалась: ${e?.message}"); Log.e("Tiho", "install", e) })
                }
            } catch (e: Exception) { f.delete(); main.post { busy.value = null; toast("Загрузка не удалась: ${e.message}") } }
        }
    }

    /** Updates through the same pinned AMO path (never Gecko's silent background updates). */
    fun checkUpdate(r: Row) {
        busy.value = "Проверяю обновление ${r.name}…"
        io.execute {
            try {
                val a = AddonPolicy.parseAddon(JSONObject(Net.getText(AddonPolicy.addonUrl(r.id), 2L * 1024 * 1024)))
                main.post {
                    busy.value = null
                    if (a.version == r.version) toast("${r.name}: установлена последняя версия ${r.version}")
                    else {
                        if (r.trusted) toast("После обновления доверие на страницах оплаты нужно подтвердить заново")
                        confirmAndInstall(a)
                    }
                }
            } catch (e: Exception) { main.post { busy.value = null; toast("Ошибка: ${e.message}") } }
        }
    }

    // ------------------------------------------------------------------ manage
    fun setEnabled(r: Row, on: Boolean) {
        (if (on) ctl.enable(r.ext, WebExtensionController.EnableSource.USER) else ctl.disable(r.ext, WebExtensionController.EnableSource.USER))
            .accept({ refresh() }, { e -> toast("Ошибка: ${e?.message}") })
    }

    fun setPrivate(r: Row, on: Boolean) = ctl.setAllowedInPrivateBrowsing(r.ext, on).accept({ refresh() }, { e -> toast("Ошибка: ${e?.message}") })

    fun uninstall(r: Row) = ctl.uninstall(r.ext).accept({
        val p = AddonPolicy.parsePins(prefs.addonPins); p.remove(r.id); prefs.addonPins = AddonPolicy.pinsJson(p)
        val t = AddonPolicy.parseTrust(prefs.addonPaymentTrust); t.remove(r.id); prefs.addonPaymentTrust = AddonPolicy.trustJson(t)
        if (r.id == AddonPolicy.UBO_ID) prefs.uboInstalled = true // removed on purpose: do not reinstall
        actions.remove(r.id); icons.remove(r.id)
        refresh()
    }, { e -> toast("Ошибка: ${e?.message}") })

    fun setTrust(r: Row, on: Boolean) {
        val pins = AddonPolicy.parsePins(prefs.addonPins)
        val t = AddonPolicy.parseTrust(prefs.addonPaymentTrust)
        if (on) {
            if (!AddonPolicy.trustable(r.id, r.version, pins)) { toast("Нельзя доверять: нет проверенного SHA-256 для версии ${r.version}. Переустановите с AMO."); return }
            t[r.id] = AddonPolicy.trustToken(pins.getValue(r.id))
        } else t.remove(r.id)
        prefs.addonPaymentTrust = AddonPolicy.trustJson(t)
        refresh()
    }

    fun setOrigin(r: Row, origin: String, granted: Boolean) {
        (if (granted) ctl.addOptionalPermissions(r.id, emptyArray(), arrayOf(origin), emptyArray())
        else ctl.removeOptionalPermissions(r.id, emptyArray(), arrayOf(origin), emptyArray())).accept({ refresh() }, { e -> toast("Ошибка: ${e?.message}") })
    }

    // ------------------------------------------------------------------ payment-safe mode
    /**
     * Mozilla-recommended add-ons ignore Gecko's quarantine (quarantineIgnoredByApp). While a «Банк» tab exists and
     * some add-on is trusted (so payment hosts are quarantined, not restricted), every enabled recommended add-on that
     * is not on the allowlist is paused with EnableSource.APP (the user's own enable state is kept) and resumed after.
     */
    fun onBankTabs(open: Boolean) {
        if (!this::ctl.isInitialized) return
        if (open) {
            val trusted = trustedForPayment()
            if (trusted.isEmpty()) return // restricted mode: no extension runs on payment hosts at all
            val paused = prefs.addonsPaused.toMutableSet()
            for (r in rows) if (r.enabled && r.recommended && r.id !in trusted && r.id !in paused) {
                paused += r.id
                ctl.disable(r.ext, WebExtensionController.EnableSource.APP).accept({ refresh() }, { })
            }
            prefs.addonsPaused = paused
        } else resumePaused()
    }

    private fun resumePaused() {
        val ids = prefs.addonsPaused
        if (ids.isEmpty()) return
        ctl.list().accept({ list ->
            for (e in list.orEmpty()) if (e.id in ids) ctl.enable(e, WebExtensionController.EnableSource.APP).accept({ refresh() }, { })
            prefs.addonsPaused = emptySet()
        }, { })
    }

    // ------------------------------------------------------------------ built-in extension (userscripts)
    private val pushLater = Runnable { pushConfig() }
    fun schedulePush() { main.removeCallbacks(pushLater); main.postDelayed(pushLater, 1500) }

    fun pushConfig() {
        val port = tihoPort ?: return
        try {
            val cfg = UserScriptJs.config(app.extensions.scripts.toList(), { app.extensions.gmAll(it) }, app.engine.paymentBaseDomains(),
                app.payments.guardJs(), payDetect = true)
            org.ivanivorontsov.tiho.dist.Dist.extraScript(app)?.let { cfg.getJSONArray("scripts").put(it) }
            port.postMessage(cfg)
        } catch (e: Exception) { Log.e("Tiho", "push config", e) }
        app.engine.applyDynamicPrefs()
    }
}
