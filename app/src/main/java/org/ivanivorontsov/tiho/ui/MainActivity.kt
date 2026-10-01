package org.ivanivorontsov.tiho.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.security.SchemePolicy

class MainActivity : ComponentActivity() {
    lateinit var browser: Browser
    private var permCallback: ((Boolean) -> Unit)? = null
    private var fileCallback: ((List<Uri>?) -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permCallback?.invoke(res.values.any { it }); permCallback = null
    }
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        val uris = if (r.resultCode != RESULT_OK || d == null) null else {
            val clip = d.clipData
            if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(d.data)
        }
        fileCallback?.invoke(uris); fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TihoApp
        browser = Browser(app)
        browser.activity = this
        browser.requestPermissions = { perms, cb ->
            if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) cb(true)
            else { permCallback = cb; permLauncher.launch(perms) }
        }
        browser.pickFiles = { types, multiple, cb ->
            val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (types.size == 1) types[0] else "*/*")
            if (types.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            fileCallback = cb
            try { fileLauncher.launch(i) } catch (_: Exception) { cb(null); fileCallback = null }
        }
        when (val r = intentInput(intent)) {
            is SchemePolicy.Result.Load -> browser.open(r.url, null)
            null -> browser.newTab()
            is SchemePolicy.Result.Text -> { browser.newTab(); if (r.text.isNotBlank()) browser.navigate(r.text) }
            is SchemePolicy.Result.Blocked -> { browser.newTab(); browser.snack("Ссылка из другого приложения заблокирована: ${r.reason}") }
        }
        if (browser.tabs.isEmpty()) browser.newTab()
        browser.updateWindowSecurity()
        // Daily signed update check also happens here (the JobScheduler job does not survive reboots).
        val last = app.prefs.appLastOk.toLongOrNull() ?: 0L
        if (app.prefs.appUpdateCheck && System.currentTimeMillis() - last > 20L * 3600 * 1000) app.appUpdater.check(false)
        setContent { TihoTheme(app.prefs.theme) { BrowserApp(browser) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentInput(intent)?.let { browser.openFromIntent(it) }
    }

    /** Input from other apps goes through the scheme whitelist: file:, content:, javascript:, data:, intent: are refused. */
    private fun intentInput(i: Intent?): SchemePolicy.Result? = when (i?.action) {
        Intent.ACTION_VIEW -> SchemePolicy.fromIntent(i.dataString)
        Intent.ACTION_SEND -> SchemePolicy.fromSharedText(i.getStringExtra(Intent.EXTRA_TEXT))
        Intent.ACTION_WEB_SEARCH -> SchemePolicy.fromSharedText(i.getStringExtra(android.app.SearchManager.QUERY))
        else -> null
    }

    override fun onResume() { super.onResume(); browser.updateWindowSecurity() }

    override fun onDestroy() {
        if (isFinishing && browser.tabs.isNotEmpty()) browser.exit {}
        browser.activity = null
        browser.geckoView = null
        super.onDestroy()
    }

    fun exitApp() = browser.exit { finishAndRemoveTask() }
}
