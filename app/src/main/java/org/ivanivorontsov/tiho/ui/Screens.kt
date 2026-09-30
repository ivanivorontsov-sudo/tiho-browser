package org.ivanivorontsov.tiho.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.Prefs
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.browser.Screen
import org.ivanivorontsov.tiho.browser.UiDialog
import org.ivanivorontsov.tiho.ext.UserScript

private fun Browser.back() { screen.value = Screen.Browser }

@Composable
fun TabsScreen(b: Browser) {
    SubScreen("Вкладки (${b.tabs.size})", { b.back() }, actions = {
        IconButton(onClick = { b.newTab(incognito = true) }) { Icon(Icons.Outlined.VisibilityOff, "Новая инкогнито-вкладка") }
        IconButton(onClick = { b.newTab() }) { Icon(Icons.Filled.Add, "Новая вкладка") }
    }) {
        items(b.tabs.toList(), key = { it.id }) { t ->
            val cur = t === b.current.value
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { b.select(t) },
                colors = CardDefaults.cardColors(containerColor = if (cur) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (t.incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Public, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title.value.ifBlank { if (t.isHome) "Домашняя страница" else t.url.value }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(if (t.isHome) (if (t.incognito) "Инкогнито" else "Тихо") else t.url.value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { b.closeTab(t) }) { Icon(Icons.Filled.Close, "Закрыть вкладку") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                if (b.tabs.any { it.incognito }) TextButton(onClick = { b.closeAll(incognitoOnly = true) }) { Text("Закрыть инкогнито") }
                TextButton(onClick = { b.closeAll() }) { Text("Закрыть все") }
            }
        }
    }
}

@Composable
fun BookmarksScreen(b: Browser) {
    SubScreen("Закладки", { b.back() }) {
        if (b.app.bookmarks.items.isEmpty()) item { Text("Закладок пока нет", Modifier.padding(16.dp)) }
        items(b.app.bookmarks.items.toList(), key = { it.url }) { m ->
            ListItem(
                headlineContent = { Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(m.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Outlined.BookmarkBorder, null) },
                trailingContent = { IconButton(onClick = { b.app.bookmarks.remove(m) }) { Icon(Icons.Outlined.Delete, "Удалить") } },
                modifier = Modifier.clickable { b.back(); b.current.value?.load(m.url) },
            )
        }
    }
}

@Composable
fun DownloadsScreen(b: Browser) {
    val ctx = LocalContext.current
    SubScreen("Загрузки", { b.back() }, actions = {
        if (b.app.downloads.items.isNotEmpty()) TextButton(onClick = { b.app.downloads.clear() }) { Text("Очистить список") }
    }) {
        if (b.app.downloads.items.isEmpty()) item { Text("Загрузок пока нет. Файлы сохраняются в папку «Загрузки».", Modifier.padding(16.dp)) }
        items(b.app.downloads.items.toList(), key = { it.id }) { d ->
            ListItem(
                headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(DateFormat.format("dd.MM.yyyy HH:mm", d.time).toString() + " · " + (d.url.takeIf { !it.startsWith("data:") && !it.startsWith("blob:") } ?: "из страницы").take(60)) },
                leadingContent = { Icon(Icons.Outlined.Description, null) },
                trailingContent = { IconButton(onClick = { b.app.downloads.remove(d) }) { Icon(Icons.Outlined.Close, "Убрать") } },
                modifier = Modifier.clickable {
                    try {
                        val uri = d.uri?.let(Uri::parse)
                        if (uri != null) ctx.startActivity(Intent(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        else ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
                    } catch (_: Exception) { b.toast("Нет приложения для открытия файла") }
                },
            )
        }
    }
}

@Composable
fun SettingsScreen(b: Browser) {
    val p = b.prefs
    SubScreen("Настройки", { b.back() }) {
        item {
            NavRow("Приватность и безопасность", "Отпечатки, cookies, HTTPS, WebRTC, DNS, очистка данных", Icons.Outlined.PrivacyTip) { b.screen.value = Screen.Privacy }
            NavRow("Блокировка рекламы и фильтры", "Списки, обновление, свои правила, исключения", Icons.Outlined.Shield) { b.screen.value = Screen.Filters }
            NavRow("Расширения", "Встроенные расширения и пользовательские скрипты", Icons.Outlined.Extension) { b.screen.value = Screen.Extensions }
            SectionHeader("Общие")
            ChoiceRow("Поисковая система", Prefs.SEARCH_ENGINES, p.searchEngine) { p.searchEngine = it }
            ChoiceRow("Тема", listOf(0 to "Как в системе", 1 to "Светлая", 2 to "Тёмная"), p.theme) { p.theme = it }
            SwitchRow("JavaScript", "Отключение ломает большинство сайтов", p.javascript) { p.javascript = it }
            SwitchRow("Версия для ПК по умолчанию", null, p.desktopDefault) { p.desktopDefault = it }
            SwitchRow("Новые вкладки — инкогнито", "Каждая вкладка в отдельном, не сохраняемом профиле", p.incognitoDefault) { p.incognitoDefault = it }
            ChoiceRow("Масштаб текста", listOf(85 to "85%", 100 to "100%", 115 to "115%", 130 to "130%", 150 to "150%"), p.textZoom) { p.textZoom = it }
            SwitchRow("Запретить скриншоты", "FLAG_SECURE: скрывает содержимое в списке недавних приложений", p.flagSecure) { p.flagSecure = it }
            NavRow("О приложении", "Версия ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info) { b.screen.value = Screen.About }
        }
    }
}

@Composable
fun PrivacyScreen(b: Browser) {
    val p = b.prefs
    SubScreen("Приватность", { b.screen.value = Screen.Settings }) {
        item {
            SectionHeader("Данные")
            SwitchRow("Стирать всё при выходе", "Cookies, кэш, localStorage, IndexedDB, история, Service Workers", p.clearOnExit) { p.clearOnExit = it }
            SwitchRow("Очищать сайт при закрытии вкладки", "Удаляет cookies и хранилище сайтов вкладки, если они не открыты в других", p.clearOnTabClose) { p.clearOnTabClose = it }
            SwitchRow("Cookies", null, p.cookies) { p.cookies = it }
            SwitchRow("Разрешить сторонние cookies", "По умолчанию выключено: защита от межсайтового слежения", p.thirdPartyCookies, enabled = p.cookies) { p.thirdPartyCookies = it }
            ListItem(headlineContent = { Text("Очистить данные сейчас") }, leadingContent = { Icon(Icons.Outlined.LocalFireDepartment, null) },
                modifier = Modifier.clickable { b.clearNow() })

            SectionHeader("Соединение")
            SwitchRow("Только HTTPS", "Автоповышение до HTTPS, предупреждение перед HTTP", p.httpsOnly) { p.httpsOnly = it }
            SwitchRow("DNS-over-HTTPS", "Экспериментально: локальный прокси, DNS-запросы шифруются", p.doh) { p.doh = it; b.restartDoh() }
            if (p.doh) ChoiceRow("DoH-провайдер", Prefs.DOH_PROVIDERS, p.dohProvider) { p.dohProvider = it; b.restartDoh() }
            ChoiceRow("WebRTC", listOf(0 to "Отключён (нет утечки IP)", 1 to "Только через relay (скрыть локальный IP)", 2 to "Разрешён"), p.webrtcMode) { p.webrtcMode = it }

            SectionHeader("Отслеживание")
            SwitchRow("Удалять трекинговые параметры", "utm_*, fbclid, gclid, yclid, _openstat и ещё ~150", p.stripParams) { p.stripParams = it }
            SwitchRow("Разворачивать редиректы и AMP", "Прямые ссылки вместо google.com/url, vk.com/away, AMP", p.unwrapRedirects) { p.unwrapRedirects = it }
            SwitchRow("Блокировать ping и beacon", "<a ping>, navigator.sendBeacon", p.blockPings) { p.blockPings = it }
            SwitchRow("Global Privacy Control", "Sec-GPC: 1 и navigator.globalPrivacyControl", p.gpc) { p.gpc = it }
            SwitchRow("Do Not Track", "DNT: 1", p.dnt) { p.dnt = it }
            SwitchRow("Строгий Referer", "Не передавать Referer другим сайтам (политика same-origin)", p.strictReferrer) { p.strictReferrer = it }
            SwitchRow("Блокировать всплывающие окна", null, p.blockPopups) { p.blockPopups = it }
            SwitchRow("Блокировать авто-переадресации", "Строгий режим: переходы на другие сайты без нажатия. Может мешать входу через сторонние сервисы", p.blockAutoRedirects) { p.blockAutoRedirects = it }

            SectionHeader("Отпечаток браузера")
            SwitchRow("Защита от отпечатков", "Шум Canvas/Audio/WebGL/шрифтов (как в Brave), подмена характеристик устройства", p.antiFp) { p.antiFp = it }
            SwitchRow("Общий User-Agent", "Скрывает модель телефона и версию Android, урезает Client Hints", p.genericUa) { p.genericUa = it }
            SwitchRow("Отключить WebGL", "Сильнее защищает, но ломает карты и 3D", p.blockWebGl) { p.blockWebGl = it }
            SwitchRow("Блокировать датчики", "Акселерометр, гироскоп, ориентация, батарея", p.blockSensors) { p.blockSensors = it }
            SwitchRow("Часовой пояс UTC", "Как в Tor Browser; сайты покажут неверное местное время", p.spoofTimezone) { p.spoofTimezone = it }
            SwitchRow("Разрешить DRM", "Защищённое видео (Widevine) — даёт сайтам уникальный ID устройства", p.drm) { p.drm = it }
        }
    }
}

@Composable
fun FiltersScreen(b: Browser) {
    val p = b.prefs
    val ad = b.app.adblock
    var status by remember { mutableStateOf(ad.status) }
    var stats by remember { mutableStateOf(ad.ruleStats) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { status = ad.status; stats = ad.ruleStats; delay(500) } }
    var rules by remember { mutableStateOf(p.customRules) }
    var urls by remember { mutableStateOf(p.extraListUrls) }
    val rebuild = { ad.rebuildAsync() }
    SubScreen("Фильтры", { b.screen.value = Screen.Settings }) {
        item {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(status, fontWeight = FontWeight.Medium)
                    if (stats.isNotBlank()) Text(stats, style = MaterialTheme.typography.bodySmall)
                    Text("Обновлено: " + p.lastFilterUpdate.ifBlank { "встроенные списки из сборки" }, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(enabled = !busy, onClick = {
                        busy = true
                        ad.updateLists({ s -> status = s }) { ok, fail -> busy = false; b.toast("Обновлено: $ok, ошибок: $fail") }
                    }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Обновление…" else "Обновить фильтры") }
                }
            }
            SwitchRow("Блокировка рекламы и трекеров", null, p.adblock) { p.adblock = it }
        }
        val groups = ad.allLists().groupBy { it.group }
        val names = mapOf("ads" to "Реклама", "ru" to "Русскоязычные сайты", "privacy" to "Приватность", "security" to "Безопасность", "cookies" to "Cookie-баннеры", "user" to "Пользовательские")
        for ((g, lists) in groups) {
            item { SectionHeader(names[g] ?: g) }
            items(lists, key = { it.id }) { l ->
                if (g == "cookies") {
                    ListItem(headlineContent = { Text(l.title) }, supportingContent = { Text(l.description) })
                } else {
                    SwitchRow(l.title, l.description, l.id in p.enabledLists, enabled = p.adblock) { on ->
                        p.enabledLists = if (on) p.enabledLists + l.id else p.enabledLists - l.id
                        rebuild()
                    }
                }
            }
        }
        item {
            SectionHeader("Свои правила (синтаксис uBlock/ABP)")
            OutlinedTextField(rules, { rules = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 120.dp),
                placeholder = { Text("||example.com^\nexample.org##.banner") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            SectionHeader("Дополнительные списки (URL, по одному в строке)")
            OutlinedTextField(urls, { urls = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("https://…/list.txt") }, textStyle = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(16.dp)) {
                Button(onClick = { p.customRules = rules; p.extraListUrls = urls; rebuild(); b.toast("Сохранено; новые списки загрузятся при «Обновить фильтры»") }) { Text("Сохранить") }
            }
            SectionHeader("Исключения (защита выключена)")
            if (p.allowlist.isEmpty()) Text("Нет. Отключить защиту для сайта можно, нажав на щит в адресной строке.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        items(p.allowlist.sorted()) { s ->
            ListItem(headlineContent = { Text(s) }, trailingContent = { IconButton(onClick = { p.allowlist = p.allowlist - s }) { Icon(Icons.Outlined.Delete, "Удалить") } })
        }
    }
}

@Composable
fun ExtensionsScreen(b: Browser) {
    val ext = b.app.extensions
    val ctx = LocalContext.current
    var urlDialog by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    SubScreen("Расширения", { b.back() }, actions = {
        IconButton(onClick = { urlDialog = true }) { Icon(Icons.Filled.Add, "Установить скрипт") }
    }) {
        item { SectionHeader("Встроенные") }
        items(ext.builtins, key = { it.id }) { x ->
            key(tick) {
                SwitchRow(x.title, x.description, ext.builtinEnabled(x)) { on ->
                    ext.setBuiltin(x, on); tick++
                    if (x.id == "cookies") b.app.adblock.rebuildAsync()
                }
            }
        }
        item { SectionHeader("Пользовательские скрипты") }
        if (ext.scripts.isEmpty()) item { Text("Нет установленных скриптов", Modifier.padding(horizontal = 16.dp)) }
        items(ext.scripts.toList(), key = { it.id }) { s -> ScriptRow(b, s) }
        item {
            Column(Modifier.padding(16.dp)) {
                Row {
                    OutlinedButton(onClick = { urlDialog = true }) { Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(6.dp)); Text("По ссылке") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { b.current.value?.load("https://greasyfork.org/ru"); b.back() }) { Icon(Icons.Outlined.TravelExplore, null); Spacer(Modifier.width(6.dp)); Text("Greasy Fork") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Поддерживаются userscripts (Greasemonkey/Tampermonkey): @match, @include, @exclude, @run-at, @require, @noframes, " +
                    "GM_getValue/SetValue/DeleteValue/ListValues, GM_addStyle, GM_xmlhttpRequest (через fetch, с ограничениями CORS), GM_openInTab, GM_setClipboard. " +
                    "Откройте ссылку на *.user.js — Тихо предложит установить скрипт.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Расширения из Chrome Web Store (.crx) не поддерживаются: в Android System WebView нет среды WebExtensions. " +
                    "Популярные функции (uBlock Origin, I don't care about cookies, SponsorBlock-lite, Dark Reader-lite) встроены или доступны как скрипты.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (urlDialog) {
        var u by remember { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("Установить userscript") },
            text = { OutlinedTextField(u, { u = it }, singleLine = true, label = { Text("URL .user.js") }) },
            confirmButton = { TextButton(onClick = { urlDialog = false; b.offerUserscript(u.trim()) }) { Text("Загрузить") } },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ScriptRow(b: Browser, s: UserScript) {
    val ext = b.app.extensions
    ListItem(
        headlineContent = { Text(s.name + if (s.version.isNotBlank()) "  v${s.version}" else "") },
        supportingContent = {
            Column {
                if (s.description.isNotBlank()) Text(s.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text((s.matches + s.includes).take(3).joinToString(", "), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = { Icon(Icons.Outlined.Code, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    b.dialog.value = UiDialog("Удалить скрипт?", s.name, "Удалить", onConfirm = { ext.delete(s.id) })
                }) { Icon(Icons.Outlined.Delete, "Удалить") }
                Switch(s.enabled, { ext.setEnabled(s.id, it) })
            }
        },
    )
}

@Composable
fun AboutScreen(b: Browser) {
    SubScreen("О приложении", { b.screen.value = Screen.Settings }) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Тихо ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Лёгкий приватный браузер на Android System WebView. Без аналитики, телеметрии, сервисов Google и аккаунтов. " +
                    "Приложение само не обращается к сети — только сайты, которые вы открываете, и (по кнопке) серверы списков фильтров.")
                Spacer(Modifier.height(12.dp))
                Text("Компоненты: Brave adblock-rust (MPL-2.0), скриптлеты и ресурсы uBlock Origin (GPL-3.0), списки EasyList/EasyPrivacy/RU AdList (GPL-3.0/CC BY-SA), " +
                    "uAssets (GPL-3.0), AdGuard filters (GPL-3.0), Peter Lowe's list, Jetpack Compose и AndroidX (Apache-2.0).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Движок страниц — System WebView (Chromium), обновляется через Google Play. Его сетевые настройки и сбор метрик WebView отключены через манифест.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Движок: " + (androidx.webkit.WebViewCompat.getCurrentWebViewPackage(b.app)?.let { "${it.packageName} ${it.versionName}" } ?: "неизвестно"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
