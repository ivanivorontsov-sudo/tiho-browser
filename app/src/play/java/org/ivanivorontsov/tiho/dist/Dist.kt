package org.ivanivorontsov.tiho.dist

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.Prefs
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.ui.SectionHeader
import org.mozilla.geckoview.GeckoRuntimeSettings

object Dist {
    const val NAME = "play"

    fun init(app: TihoApp) {}

    fun appChecks(app: TihoApp) = false

    fun onLaunch(app: TihoApp) {}

    fun checkApp(app: TihoApp, done: () -> Unit): Boolean = false

    fun applyDns(s: GeckoRuntimeSettings, p: Prefs) { s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF) }

    fun LazyListScope.dnsItems(p: Prefs) {}

    fun LazyListScope.appUpdateItems(b: Browser) {
        item { SectionHeader("Браузер") }
        item {
            ListItem(headlineContent = { Text("Тихо ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                supportingContent = { Text("GeckoView ${BuildConfig.GECKO_VERSION}\nОбновления браузера приходят через Google Play.") })
        }
    }

    @Composable
    fun UpdateBanner(b: Browser) {}
}
