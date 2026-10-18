package org.ivanivorontsov.tiho.dist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.Prefs
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.browser.Browser.Screen
import org.ivanivorontsov.tiho.ui.Banner
import org.ivanivorontsov.tiho.ui.ChoiceRow
import org.ivanivorontsov.tiho.ui.SectionHeader
import org.ivanivorontsov.tiho.ui.SwitchRow
import org.ivanivorontsov.tiho.update.AppUpdater
import org.ivanivorontsov.tiho.update.UpdateJob
import org.mozilla.geckoview.GeckoRuntimeSettings

object Dist {
    const val NAME = "github"

    lateinit var updater: AppUpdater
        private set

    val DOH_PROVIDERS = listOf(
        0 to "Выключен (системный DNS)",
        1 to "Mozilla (Cloudflare)",
        2 to "Quad9 (Швейцария)",
        4 to "AdGuard DNS (с блокировкой рекламы)",
    )

    fun dohUri(mode: Int): String? = when (mode) {
        1 -> "https://mozilla.cloudflare-dns.com/dns-query"
        2 -> "https://dns.quad9.net/dns-query"
        4 -> "https://dns.adguard-dns.com/dns-query"
        else -> null
    }

    private val dohMode = mutableStateOf(-1)
    private val dohStrict = mutableStateOf(false)

    private fun load(p: Prefs) {
        if (dohMode.value >= 0) return
        dohMode.value = p.sp.getInt("doh_mode", 0).let { if (it == 3) 2 else it }
        dohStrict.value = p.sp.getBoolean("doh_strict", false)
    }

    fun init(app: TihoApp) { updater = AppUpdater(app); load(app.prefs) }

    fun appChecks(app: TihoApp) = app.prefs.appUpdateCheck

    fun onLaunch(app: TihoApp) {
        val last = app.prefs.appLastOk.toLongOrNull() ?: 0L
        if (app.prefs.appUpdateCheck && System.currentTimeMillis() - last > 20L * 3600 * 1000) updater.check(false)
    }

    fun checkApp(app: TihoApp, done: () -> Unit): Boolean {
        if (!app.prefs.appUpdateCheck) return false
        updater.check(manual = false) { done() }
        return true
    }

    fun applyDns(s: GeckoRuntimeSettings, p: Prefs) {
        load(p)
        val uri = dohUri(dohMode.value)
        if (uri != null) {
            s.setTrustedRecursiveResolverUri(uri)
            s.setTrustedRecursiveResolverMode(if (dohStrict.value) GeckoRuntimeSettings.TRR_MODE_ONLY else GeckoRuntimeSettings.TRR_MODE_FIRST)
        } else s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
    }

    fun LazyListScope.dnsItems(p: Prefs) {
        load(p)
        item {
            ChoiceRow("DNS через HTTPS (DoH)", DOH_PROVIDERS, dohMode.value) {
                dohMode.value = it; p.sp.edit().putInt("doh_mode", it).apply(); p.changed("doh_mode")
            }
        }
        if (dohMode.value != 0) item {
            SwitchRow("Только DoH (без отката на системный DNS)", null, dohStrict.value) {
                dohStrict.value = it; p.sp.edit().putBoolean("doh_strict", it).apply(); p.changed("doh_strict")
            }
        }
    }

    private val screenSpoof = mutableStateOf<Boolean?>(null)

    fun screenSpoofOn(p: Prefs): Boolean = screenSpoof.value ?: p.sp.getBoolean("screen_spoof", false).also { screenSpoof.value = it }

    fun extraScript(app: TihoApp): org.json.JSONObject? {
        if (!screenSpoofOn(app.prefs)) return null
        return org.json.JSONObject().put("id", "tiho-screen").put("code", org.ivanivorontsov.tiho.ext.ScreenSize.js())
            .put("matches", org.json.JSONArray(listOf("*://*/*"))).put("runAt", "document_start")
            .put("allFrames", true).put("trusted", false).put("values", org.json.JSONObject())
    }

    fun setScreenSpoof(app: TihoApp, on: Boolean) {
        screenSpoof.value = on
        app.prefs.sp.edit().putBoolean("screen_spoof", on).apply()
        app.addons.schedulePush()
    }

    fun LazyListScope.fingerprintItems(b: Browser) {
        item {
            SwitchRow("Подменять размер экрана (360x800)", "Сайты видят типовой размер экрана: 360x800 на большинстве телефонов, на более широких — ближайший распространённый. Сайты могут распознать подмену. На платёжных сайтах не действует. По умолчанию выключено", screenSpoofOn(b.prefs)) { setScreenSpoof(b.app, it) }
        }
    }

    fun LazyListScope.appUpdateItems(b: Browser) {
        val p = b.prefs
        val up = updater
        item { SectionHeader("Браузер (APK)") }
        item {
            ListItem(headlineContent = { Text("Тихо ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                supportingContent = { Text("GeckoView ${BuildConfig.GECKO_VERSION}\nИсправления безопасности Gecko приходят только с новым APK — проверка ежедневно.\n" +
                    (p.appUpdateStatus.ifEmpty { "Ещё не проверялось" }) + (if (p.appLastCheck.isNotEmpty()) "\nПроверено: ${p.appLastCheck}" else "")) })
        }
        item {
            val ctx = LocalContext.current
            var busy by remember { mutableStateOf(false) }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { busy = true; up.check(true) { s -> b.main.post { busy = false; b.toast(s) } } }) { Text("Проверить сейчас") }
                up.available.value?.let { info ->
                    Button(enabled = up.progress.value == null, onClick = {
                        if (!up.canInstall()) up.openInstallPermissionSettings(ctx) else up.install(info) { s -> b.toast(s) }
                    }) { Text("Установить ${info.versionName}") }
                }
            }
        }
        up.progress.value?.let { item { Text(it, Modifier.padding(16.dp)) } }
        item {
            val ctx = LocalContext.current
            SwitchRow("Проверять обновления браузера ежедневно", "update-v2.json, подпись Ed25519; APK: SHA-256, размер и сертификат подписи должны совпасть", p.appUpdateCheck) { p.appUpdateCheck = it; UpdateJob.schedule(ctx, p.contentAuto || it, p.contentWifiOnly) }
        }
    }

    @Composable
    fun UpdateBanner(b: Browser) {
        val up = updater.available.value
        if (up != null && b.prefs.updateBannerDismissed != up.versionName) {
            Banner(MaterialTheme.colorScheme.primary, Icons.Outlined.SystemUpdate,
                "Доступно обновление Тихо ${up.versionName}" + (if (up.geckoVersion.isNotEmpty()) " (GeckoView ${up.geckoVersion.substringBefore('.')})" else "") + " — исправления безопасности движка") {
                TextButton(onClick = { b.screen.value = Screen.UPDATES }) { Text("Обновить") }
                IconButton(onClick = { b.prefs.updateBannerDismissed = up.versionName }) { Icon(Icons.Filled.Close, "Скрыть") }
            }
        } else if (b.prefs.appUpdateCheck && updater.stale()) {
            Banner(MaterialTheme.colorScheme.error, Icons.Outlined.GppMaybe, "Более 14 дней не удаётся проверить обновления браузера — движок может быть устаревшим") {
                TextButton(onClick = { b.screen.value = Screen.UPDATES }) { Text("Проверить") }
            }
        }
    }
}
