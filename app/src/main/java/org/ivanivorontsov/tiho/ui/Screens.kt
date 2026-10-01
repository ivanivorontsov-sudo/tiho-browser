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
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.update.ContentPacks
import org.ivanivorontsov.tiho.update.TrustedKeys

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
            NavRow("Обновления", "Подписанные пакеты списков, обновление приложения, движок WebView", Icons.Outlined.SystemUpdate) { b.screen.value = Screen.Updates }
            SectionHeader("Общие")
            ChoiceRow("Поисковая система", Prefs.SEARCH_ENGINES, p.searchEngine) { p.searchEngine = it }
            ChoiceRow("Тема", listOf(0 to "Как в системе", 1 to "Светлая", 2 to "Тёмная"), p.theme) { p.theme = it }
            SwitchRow("JavaScript", "Отключение ломает большинство сайтов", p.javascript) { p.javascript = it }
            SwitchRow("Версия для ПК по умолчанию", null, p.desktopDefault) { p.desktopDefault = it }
            SwitchRow("Новые вкладки — инкогнито", "Каждая вкладка в отдельном, не сохраняемом профиле", p.incognitoDefault) { p.incognitoDefault = it }
            ChoiceRow("Масштаб текста", listOf(85 to "85%", 100 to "100%", 115 to "115%", 130 to "130%", 150 to "150%"), p.textZoom) { p.textZoom = it }
            SwitchRow("Запретить скриншоты", "FLAG_SECURE везде. В инкогнито и в режиме оплаты включается автоматически", p.flagSecure) { p.flagSecure = it }
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

            SectionHeader("Безопасность")
            SwitchRow("Защита от фишинга", "Списки malware-filter (phishing-filter, URLhaus): предупреждение перед опасным сайтом. Проверка локальная, без запросов наружу", p.phishing) { p.phishing = it; b.app.adblock.rebuildAsync { b.app.adblock.rebuildThreats() } }
            SwitchRow("Сторонние cookies в режиме оплаты", "По умолчанию для вкладок «Банк» (нужно некоторым 3-D Secure). Меняется и в каждой вкладке", p.paymentThirdPartyCookies) { p.paymentThirdPartyCookies = it }
            ListItem(headlineContent = { Text("Сайты оплаты, добавленные вами") },
                supportingContent = { Text(if (p.paymentUserDomains.isEmpty()) "Нет. Встроенный список: ${org.ivanivorontsov.tiho.security.PaymentDomains.BUILTIN.size} доменов + ${p.paymentKnown.size} из подписанных пакетов" else p.paymentUserDomains.sorted().joinToString(", ")) },
                trailingContent = { if (p.paymentUserDomains.isNotEmpty()) TextButton(onClick = { p.paymentUserDomains = emptySet(); b.app.payments.refresh() }) { Text("Очистить") } })

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
    var pending by remember { mutableStateOf(ad.pendingLists.firstOrNull()) }
    LaunchedEffect(Unit) { while (true) { if (pending == null) pending = ad.pendingLists.firstOrNull(); delay(700) } }
    pending?.let { pl ->
        AlertDialog(onDismissRequest = {},
            title = { Text(if (pl.oldHash == null) "Новый список фильтров" else "Список фильтров изменился") },
            text = { Text("${pl.title}\n${pl.url}\n\n" + (pl.oldHash?.let { "Было SHA-256: ${UserScriptPin.pretty(it)}\n" } ?: "") +
                "SHA-256: ${UserScriptPin.pretty(pl.newHash)}\n\nСторонний список может скрывать или ломать сайты. Применить это содержимое?") },
            confirmButton = { TextButton(onClick = { ad.confirmList(pl.id); pending = null }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { ad.rejectList(pl.id); pending = null }) { Text("Отклонить") } })
    }
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
                if (g == "cookies" || l.id == "payment-unbreak") {
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
            val insecure = urls.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("https://") }
            Text(if (insecure.isNotEmpty()) "Игнорируются (только https://): ${insecure.joinToString(", ").take(120)}"
                 else "Только HTTPS. При первой загрузке и при каждом изменении содержимого Тихо покажет SHA-256 и попросит подтвердить.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = if (insecure.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
        val catalog = ext.catalog(b.app.contentPacks.text("userscripts/catalog.json"))
        if (catalog.isNotEmpty()) {
            item { SectionHeader("Каталог (из подписанного пакета, хэши закреплены)") }
            items(catalog, key = { "cat-" + it.url }) { c ->
                ListItem(headlineContent = { Text(c.name) },
                    supportingContent = { Text(c.description + "\nSHA-256 " + UserScriptPin.pretty(c.sha256).take(19) + "…", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { TextButton(onClick = { b.offerUserscript(c.url, c.sha256) }) { Text("Установить") } })
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
                Text("Скрипты и @require загружаются только по HTTPS; SHA-256 показывается и закрепляется при установке, любое изменение требует подтверждения. " +
                    "На сайтах банков и оплаты скрипты не запускаются (кроме отмеченных «доверять на оплате»).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
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
            text = { OutlinedTextField(u, { u = it }, singleLine = true, label = { Text("URL .user.js (только https://)") }) },
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
                Text("SHA-256 " + UserScriptPin.pretty(s.sha256).take(29) + "…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                if (s.tampered) Text("⚠ Код не совпадает с закреплённым хэшем — скрипт отключён. Переустановите его.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.trustedPayment && s.runsOnPayment, { on ->
                        if (on) b.dialog.value = UiDialog("Доверять на оплате?", "«${s.name}» будет работать и в «Безопасном режиме оплаты» — на страницах банков, рядом с данными карты. " +
                            "Доверие закрепляется за SHA-256 ${UserScriptPin.pretty(s.bundleHash).take(19)}… и снимается при любом изменении кода.", "Доверять",
                            onConfirm = { ext.setTrustedPayment(s.id, true) })
                        else ext.setTrustedPayment(s.id, false)
                    })
                    Text("доверять на оплате", style = MaterialTheme.typography.labelMedium)
                }
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
                    "Приложение само обращается к сети только за подписанными обновлениями (репозиторий tiho-updates на GitHub, раз в сутки, можно выключить) и по кнопке — к серверам списков фильтров.")
                Spacer(Modifier.height(12.dp))
                Text("Компоненты: Brave adblock-rust (MPL-2.0), скриптлеты и ресурсы uBlock Origin (GPL-3.0), списки EasyList/EasyPrivacy/RU AdList (GPL-3.0/CC BY-SA), " +
                    "uAssets (GPL-3.0), AdGuard filters (GPL-3.0), Peter Lowe's list, Jetpack Compose и AndroidX (Apache-2.0).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Движок страниц — System WebView (Chromium), обновляется через Google Play. Его сетевые настройки и сбор метрик WebView отключены через манифест.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Движок: " + (androidx.webkit.WebViewCompat.getCurrentWebViewPackage(b.app)?.let { "${it.packageName} ${it.versionName}" } ?: "неизвестно") +
                    " · минимально безопасная версия: ${b.app.contentPacks.minWebViewMajor}", style = MaterialTheme.typography.bodySmall)
                Text("Мост JS: " + (b.current.value?.bridgeKind ?: "—") + " · скрипты: " + org.ivanivorontsov.tiho.privacy.Scripts.source, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun UpdatesScreen(b: Browser) {
    val p = b.prefs
    val cp = b.app.contentPacks
    val up = b.app.appUpdater
    var busy by remember { mutableStateOf(false) }
    var appBusy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(700); tick++ } }
    val ctx = LocalContext.current
    SubScreen("Обновления", { b.screen.value = Screen.Settings }) {
        item {
            key(tick) {
                val m = cp.manifest
                val e = b.engine.value
                SectionHeader("Приложение")
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Тихо ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.Medium)
                        Text(p.appUpdateStatus.ifBlank { "Ещё не проверялось" }, style = MaterialTheme.typography.bodySmall)
                        if (p.appLastCheck.isNotBlank()) Text("Проверено: ${p.appLastCheck}", style = MaterialTheme.typography.bodySmall)
                        up.progress.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(enabled = !appBusy, onClick = { appBusy = true; up.check(manual = true) { appBusy = false; b.toast(it) } }) { Text(if (appBusy) "Проверка…" else "Проверить обновление") }
                            up.available.value?.let { info ->
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    if (!up.canInstall()) { b.toast("Разрешите установку обновлений для Тихо"); up.openInstallPermissionSettings(ctx) }
                                    else b.dialog.value = UiDialog("Обновить до ${info.versionName}?",
                                        "APK ${"%.1f".format(info.size / 1048576.0)} МБ будет загружен по HTTPS, проверены подпись update.json (Ed25519), SHA-256 и сертификат подписи APK. Затем Android попросит подтвердить установку.\n\n${info.notes}",
                                        "Загрузить и установить", onConfirm = { up.install(info) { b.toast(it) } })
                                }) { Text("Установить ${info.versionName}") }
                            }
                        }
                    }
                }
                SwitchRow("Проверять обновление приложения", "Раз в сутки вместе с пакетом списков; устанавливается только после вашего подтверждения", p.appUpdateCheck) { p.appUpdateCheck = it }

                SectionHeader("Подписанный пакет списков и скриптов")
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (m == null) "Используются встроенные списки" else "Пакет v${m.version} от ${m.created.take(10)}", fontWeight = FontWeight.Medium)
                        if (m != null) {
                            Text("Действует до ${java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru")).format(java.util.Date(m.expires * 1000))} · файлов: ${m.files.size}", style = MaterialTheme.typography.bodySmall)
                            Text("Код из пакета (JS): " + org.ivanivorontsov.tiho.privacy.Scripts.source, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(p.contentStatus.ifBlank { "Ещё не проверялось" } + if (p.contentLastCheck.isNotBlank()) " · ${p.contentLastCheck}" else "", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(enabled = !busy && !cp.busy, onClick = { busy = true; cp.check(manual = true) { busy = false; b.toast(it); b.main.post { b.engine.value = b.engineState() } } }) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Проверка…" else "Проверить сейчас")
                        }
                    }
                }
                SwitchRow("Проверять раз в сутки", "Фоновая проверка (JobScheduler), только при достаточном заряде", p.contentAuto) { p.contentAuto = it }
                SwitchRow("Только по Wi-Fi", "Не тратить мобильный трафик", p.contentWifiOnly, enabled = p.contentAuto) { p.contentWifiOnly = it }
                SwitchRow("Только списки, без кода", "Не применять JS (шимы защиты) из пакетов — только списки и данные", p.contentNoCode) { p.contentNoCode = it; org.ivanivorontsov.tiho.privacy.Scripts.init(b.app); b.onSettingsChanged() }

                SectionHeader("Движок страниц")
                ListItem(headlineContent = { Text(e.packageName?.let { "$it ${e.versionName}" } ?: "Не удалось определить WebView") },
                    supportingContent = { Text((if (e.outdated) "⚠ Устарел: " else if (e.unknown) "⚠ " else "✓ ") + "минимум — версия ${e.minMajor} (встроено ${ContentPacks.BUILTIN_MIN_WEBVIEW}, пакеты могут только повышать)") })

                SectionHeader("Ключи подписи обновлений (Ed25519, minisign)")
                TrustedKeys.keys.forEachIndexed { i, k ->
                    ListItem(headlineContent = { Text((if (i == 0) "Основной " else "Резервный ") + k.idHex) },
                        supportingContent = { Text("SHA-256 " + k.fingerprint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) })
                }
                Text("Ключи обновлений отличаются от ключа подписи APK. Источник: github.com/ivanivorontsov-sudo/tiho-updates (публичный). " +
                    "Пакет принимается, только если подпись верна, версия больше установленной, срок действия не истёк и все SHA-256 совпадают; " +
                    "если с новыми списками блокировщик не собирается — автоматически возвращается предыдущая версия.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
