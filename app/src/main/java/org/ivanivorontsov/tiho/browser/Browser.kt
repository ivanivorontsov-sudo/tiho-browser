package org.ivanivorontsov.tiho.browser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.data.DownloadItem
import org.ivanivorontsov.tiho.ext.UserScript
import org.ivanivorontsov.tiho.privacy.DataCleaner
import org.ivanivorontsov.tiho.privacy.DohProxy
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class Screen { Browser, Tabs, Bookmarks, Downloads, Settings, Privacy, Filters, Extensions, About }

data class UiDialog(
    val title: String,
    val text: String,
    val confirm: String,
    val dismiss: String = "Отмена",
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit = {},
)

data class Snack(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

/** Tab manager + glue between WebView callbacks and the Compose UI. */
class Browser(val app: TihoApp) {
    var activity: Activity? = null
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool()
    val prefs get() = app.prefs

    private val rnd = SecureRandom()
    /** Random JS-bridge name + token per process: pages can't predict or forge bridge calls. */
    val bridgeName = "_" + (1..9).map { "abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(26)] }.joinToString("")
    val token = (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)] }.joinToString("")
    /** Farbling session seed (Brave-style): new every app start, never leaves the device. */
    val sessionSeed = java.lang.Long.toHexString(rnd.nextLong())

    val tabs = mutableStateListOf<Tab>()
    val current = mutableStateOf<Tab?>(null)
    val screen = mutableStateOf(Screen.Browser)
    val dialog = mutableStateOf<UiDialog?>(null)
    val snack = mutableStateOf<Snack?>(null)
    val fullscreen = mutableStateOf<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    val findResult = mutableStateOf("")
    val httpAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var doh: DohProxy? = null

    // Activity hooks
    var requestPermissions: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null
    var launchFileChooser: ((Intent, (Array<Uri>?) -> Unit) -> Unit)? = null

    init {
        app.prefs.onChange { main.post { onSettingsChanged() } }
        app.extensions.onChanged = { main.post { tabs.forEach { it.installScripts() } } }
        app.adblock.onReloaded = { main.post { tabs.forEach { it.resetScriptlets() } } }
    }

    fun newTab(url: String? = null, incognito: Boolean = prefs.incognitoDefault, select: Boolean = true): Tab {
        val t = Tab(this, incognito, prefs.desktopDefault)
        val idx = current.value?.let { tabs.indexOf(it) + 1 } ?: tabs.size
        tabs.add(idx.coerceIn(0, tabs.size), t)
        if (select) { current.value = t; screen.value = Screen.Browser }
        if (url != null) { if (url.contains("://") || url.startsWith("about:")) t.load(url) else navigate(url) }
        return t
    }

    fun select(t: Tab) { current.value = t; screen.value = Screen.Browser }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        t.onClosed()
        if (t.incognito && tabs.none { it.incognito }) DataCleaner.clearIncognito()
        if (current.value === t) current.value = tabs.getOrNull(i.coerceAtMost(tabs.size - 1))
        if (tabs.isEmpty()) newTab()
    }

    fun closeAll(incognitoOnly: Boolean = false) {
        tabs.filter { !incognitoOnly || it.incognito }.forEach { closeTab(it) }
    }

    fun recoverCrashedTab(t: Tab) {
        val u = t.url.value
        val i = tabs.indexOf(t)
        if (i < 0) return
        val n = Tab(this, t.incognito, t.desktop.value)
        tabs[i] = n
        if (current.value === t) current.value = n
        try { t.webView.destroy() } catch (_: Exception) {}
        if (u.isNotEmpty()) n.load(u)
        snack("Страница была перезагружена после сбоя")
    }

    /** Address bar input → URL or search. */
    fun navigate(input: String) {
        val t = current.value ?: newTab()
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("about:") -> s
            !s.contains(' ') && Regex("^[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)+(:\\d+)?(/.*)?$").matches(s) -> "https://$s"
            else -> prefs.searchUrl(s)
        }
        t.load(url)
    }

    fun goHome() {
        val t = current.value ?: return
        t.webView.stopLoading()
        t.webView.loadUrl("about:blank")
        t.webView.clearHistory()
        t.url.value = ""; t.title.value = ""; t.warning.value = null; t.blocked.intValue = 0
    }

    fun onSettingsChanged() {
        tabs.forEach { it.configure(); it.installScripts() }
        applyDoh()
        activity?.let { a ->
            if (prefs.flagSecure) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** DNS-over-HTTPS: local proxy + WebView ProxyController override. */
    fun applyDoh() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val want = prefs.doh
        if (want && doh == null) {
            val p = DohProxy(DohProxy.endpointFor(prefs.dohProvider)) {
                buildList { if (prefs.dnt) add("DNT: 1"); if (prefs.gpc) add("Sec-GPC: 1") }
            }
            io.execute {
                val port = p.start()
                main.post {
                    doh = p
                    ProxyController.getInstance().setProxyOverride(
                        ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(), { it.run() }, {})
                }
            }
        } else if (!want && doh != null) {
            ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
            doh?.stop(); doh = null
        }
    }

    fun restartDoh() { if (doh != null) { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}); doh?.stop(); doh = null }; applyDoh() }

    fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        main.post { snack.value = Snack(text, action, onAction) }
    }

    fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ external apps
    fun openExternal(tab: Tab, url: String) {
        val intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (_: Exception) { return }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val fallback = intent.getStringExtra("browser_fallback_url")
        dialog.value = UiDialog(
            "Открыть внешнее приложение?",
            "Сайт хочет открыть: ${url.take(120)}",
            "Открыть",
            onConfirm = {
                try { activity?.startActivity(intent) } catch (_: ActivityNotFoundException) {
                    if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) tab.load(fallback)
                    else toast("Нет приложения для этой ссылки")
                }
            },
        )
    }

    // ------------------------------------------------------------------ permissions
    fun onPermissionRequest(tab: Tab, req: PermissionRequest) {
        main.post {
            val wanted = req.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    (it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID && prefs.drm)
            }
            if (wanted.isEmpty()) { req.deny(); return@post }
            val names = wanted.joinToString(", ") {
                when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "камера"; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "микрофон"; else -> "защищённое видео (DRM)" }
            }
            dialog.value = UiDialog("Доступ для ${req.origin.host}", "Сайт запрашивает: $names. Разрешение действует только для этой страницы.", "Разрешить",
                onConfirm = {
                    val perms = buildList {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(Manifest.permission.CAMERA)
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add(Manifest.permission.RECORD_AUDIO)
                    }.toTypedArray()
                    if (perms.isEmpty()) req.grant(wanted.toTypedArray())
                    else requestPermissions?.invoke(perms) { ok -> if (ok) req.grant(wanted.toTypedArray()) else req.deny() } ?: req.deny()
                },
                onDismiss = { req.deny() })
        }
    }

    fun onGeolocation(origin: String, cb: GeolocationPermissions.Callback) {
        main.post {
            dialog.value = UiDialog("Геолокация", "Сайт $origin запрашивает ваше местоположение.", "Разрешить один раз",
                onConfirm = {
                    requestPermissions?.invoke(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { ok ->
                        cb.invoke(origin, ok, false)
                    } ?: cb.invoke(origin, false, false)
                },
                onDismiss = { cb.invoke(origin, false, false) })
        }
    }

    fun showFileChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val l = launchFileChooser ?: return false
        val intent = try { params.createIntent() } catch (_: Exception) { return false }
        l(intent) { cb.onReceiveValue(it) }
        return true
    }

    fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (fullscreen.value != null) { cb.onCustomViewHidden(); return }
        fullscreenCallback = cb
        fullscreen.value = view
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    fun exitFullscreen() {
        fullscreen.value = null
        fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ------------------------------------------------------------------ userscripts from the web
    fun offerUserscript(url: String) {
        io.execute {
            try {
                val s = app.extensions.download(url)
                main.post { confirmInstall(s) }
            } catch (e: Exception) { toast("Не удалось загрузить скрипт: ${e.message}") }
        }
    }

    fun confirmInstall(s: UserScript) {
        val where = (s.matches + s.includes).take(5).joinToString("\n").ifBlank { "(нигде)" }
        dialog.value = UiDialog("Установить userscript?", "«${s.name}» ${s.version}\n${s.description}\n\nБудет работать на:\n$where\n\nУстанавливайте только скрипты, которым доверяете.", "Установить",
            onConfirm = { io.execute { app.extensions.install(s); toast("Установлено: ${s.name}") } })
    }

    // ------------------------------------------------------------------ downloads
    fun onDownload(tab: Tab, url: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val name = URLUtil.guessFileName(url, cd, mime)
        val size = if (len > 0) " (%.1f МБ)".format(len / 1048576.0) else ""
        dialog.value = UiDialog("Скачать файл?", "$name$size", "Скачать", onConfirm = {
            when {
                url.startsWith("blob:") -> {
                    val id = "b" + System.nanoTime()
                    blobNames[id] = name
                    tab.webView.evaluateJavascript(blobScript(url, id, name, mime ?: "application/octet-stream"), null)
                }
                url.startsWith("data:") -> io.execute {
                    val comma = url.indexOf(',')
                    val meta = url.substring(5, comma)
                    val bytes = if (meta.endsWith(";base64")) android.util.Base64.decode(url.substring(comma + 1), 0) else Uri.decode(url.substring(comma + 1)).toByteArray()
                    main.post { saveBytes(name, meta.substringBefore(';').ifBlank { "application/octet-stream" }, bytes, "data:") }
                }
                else -> enqueue(tab, url, ua, name, mime)
            }
        })
    }

    private fun enqueue(tab: Tab, url: String, ua: String?, name: String, mime: String?) {
        try {
            val r = DownloadManager.Request(Uri.parse(url))
            tab.cookieManager().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
            r.addRequestHeader("User-Agent", tab.webView.settings.userAgentString ?: ua ?: "")
            if (prefs.dnt) r.addRequestHeader("DNT", "1")
            r.setMimeType(mime)
            r.setTitle(name)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = app.getSystemService(DownloadManager::class.java)
            val id = dm.enqueue(r)
            app.downloads.add(DownloadItem(id, name, url, System.currentTimeMillis()))
            snack("Загрузка начата: $name", "Загрузки") { screen.value = Screen.Downloads }
        } catch (e: Exception) { toast("Ошибка загрузки: ${e.message}") }
    }

    private val blobParts = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val blobNames = ConcurrentHashMap<String, String>()

    fun blobChunk(id: String, b64: String) {
        blobParts.getOrPut(id) { ByteArrayOutputStream() }.write(android.util.Base64.decode(b64, 0))
    }

    fun blobEnd(id: String, name: String, mime: String) {
        val bytes = blobParts.remove(id)?.toByteArray() ?: return
        saveBytes(blobNames.remove(id) ?: name, mime, bytes, "blob:")
    }

    private fun blobScript(url: String, id: String, name: String, mime: String) = """
        (function(){const B=window['$bridgeName'],T='$token';fetch('$url').then(r=>r.blob()).then(async b=>{
          const buf=new Uint8Array(await b.arrayBuffer());const CH=393216;
          for(let i=0;i<buf.length;i+=CH){let s='';const part=buf.subarray(i,i+CH);for(let j=0;j<part.length;j++)s+=String.fromCharCode(part[j]);B.blobChunk(T,'$id',btoa(s));}
          B.blobEnd(T,'$id',${org.json.JSONObject.quote(name)},b.type||'$mime');}).catch(e=>console.error(e));})();
    """.trimIndent()

    private fun saveBytes(name: String, mime: String, bytes: ByteArray, src: String) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val u = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                app.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
                u
            } else {
                val f = java.io.File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
                f.writeBytes(bytes); Uri.fromFile(f)
            }
            app.downloads.add(DownloadItem(-1, name, src, System.currentTimeMillis(), uri.toString()))
            snack("Сохранено: $name", "Загрузки") { screen.value = Screen.Downloads }
        } catch (e: Exception) { toast("Не удалось сохранить: ${e.message}") }
    }

    // ------------------------------------------------------------------ exit
    /** «Выход»: closes all tabs and (if enabled) wipes all browsing data. */
    fun exit(onDone: () -> Unit) {
        val wv = current.value?.webView
        val wipe = prefs.clearOnExit
        tabs.toList().forEach { t -> tabs.remove(t); if (t.webView !== wv) try { t.webView.destroy() } catch (_: Exception) {} }
        current.value = null
        if (wipe) DataCleaner.clearAll(app, wv) { main.post { try { wv?.destroy() } catch (_: Exception) {}; onDone() } }
        else { try { wv?.destroy() } catch (_: Exception) {}; onDone() }
    }

    fun clearNow() {
        DataCleaner.clearAll(app, current.value?.webView) { toast("Все данные сайтов удалены") }
    }
}
