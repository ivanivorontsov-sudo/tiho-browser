package org.ivanivorontsov.tiho.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.browser.Browser

class MainActivity : ComponentActivity() {
    lateinit var browser: Browser
    private var permCallback: ((Boolean) -> Unit)? = null
    private var fileCallback: ((Array<Uri>?) -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permCallback?.invoke(res.values.any { it }); permCallback = null
    }
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.invoke(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TihoApp
        if (app.prefs.flagSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        browser = Browser(app)
        browser.activity = this
        browser.requestPermissions = { perms, cb ->
            if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) cb(true)
            else { permCallback = cb; permLauncher.launch(perms) }
        }
        browser.launchFileChooser = { intent, cb ->
            fileCallback = cb
            try { fileLauncher.launch(intent) } catch (_: Exception) { cb(null); fileCallback = null }
        }
        browser.applyDoh()
        browser.newTab(startUrl(intent))
        setContent { TihoTheme(app.prefs.theme) { BrowserApp(browser) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startUrl(intent)?.let { browser.newTab(it) }
    }

    private fun startUrl(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_VIEW -> i.dataString
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { extractUrl(it) }
        Intent.ACTION_WEB_SEARCH -> i.getStringExtra(android.app.SearchManager.QUERY)
        else -> null
    }

    private fun extractUrl(t: String): String = Regex("https?://\\S+").find(t)?.value ?: t

    override fun onPause() {
        super.onPause()
        android.webkit.CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (isFinishing && browser.tabs.isNotEmpty()) browser.exit {}
        browser.activity = null
        super.onDestroy()
    }

    fun exitApp() = browser.exit { finishAndRemoveTask() }
}
