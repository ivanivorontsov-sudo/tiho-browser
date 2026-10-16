package org.ivanivorontsov.tiho.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.Prefs
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.browser.Browser.Screen
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.gecko.AddonPolicy
import org.ivanivorontsov.tiho.gecko.Addons
import org.ivanivorontsov.tiho.security.Homograph
import org.ivanivorontsov.tiho.update.TrustedKeys

private fun Browser.backTo(s: Screen = Screen.BROWSER) { screen.value = s }

@Composable
fun SettingsScreen(b: Browser) {
    val p = b.prefs
    SubScreen("Настройки", { b.backTo() }) {
        item { SectionHeader("Основные") }
        item { ChoiceRow("Поисковая система", Prefs.SEARCH_ENGINES, p.searchEngine) { p.searchEngine = it } }
        item { ChoiceRow("Тема", listOf(0 to "Как в системе", 1 to "Светлая", 2 to "Тёмная"), p.theme) { p.theme = it } }
        item { SwitchRow("Версия для ПК по умолчанию", null, p.desktopDefault) { p.desktopDefault = it } }
        item { SwitchRow("Блокировать всплывающие окна", null, p.blockPopups) { p.blockPopups = it } }

        item { SectionHeader("Приватность (настройки Gecko в духе Arkenfox / IronFox)") }
        item { SwitchRow("Строгая защита от отслеживания (ETP Strict)", "Трекеры, майнеры, фингерпринтеры, соцсети; полная защита cookie (Total Cookie Protection)", p.etpStrict) { p.etpStrict = it } }
        item { ChoiceRow("Защита от фингерпринтинга", listOf(0 to "FPP — как в Firefox Strict (совместимо)", 1 to "RFP — максимальная защита (может ломать сайты)"), p.fpMode) { p.fpMode = it } }
        item { SwitchRow("Только HTTPS", "HTTP-сайты открываются только после предупреждения Gecko", p.httpsOnly) { p.httpsOnly = it } }
        item { SwitchRow("Global Privacy Control", "Сигнал «не продавать мои данные» (Sec-GPC)", p.gpc) { p.gpc = it } }
        item { ChoiceRow("WebRTC", listOf(0 to "Выключен", 1 to "Без утечки IP (только адрес по умолчанию, без локальных)", 2 to "Разрешён полностью"), p.webrtcMode) { p.webrtcMode = it } }
        item { ChoiceRow("DNS через HTTPS (DoH)", Prefs.DOH_PROVIDERS, p.doh) { p.doh = it } }
        if (p.doh != 0) item { SwitchRow("Только DoH (без отката на системный DNS)", null, p.dohStrict) { p.dohStrict = it } }
        item { SwitchRow("Обрезать Referer между сайтами", "Другим сайтам передаётся только домен (XOriginTrimmingPolicy=2)", p.referrerTrim) { p.referrerTrim = it } }
        item { SwitchRow("Удалять трекинг-параметры из ссылок", "utm_*, fbclid, gclid… (query stripping)", p.stripParams) { p.stripParams = it } }
        item { SwitchRow("Отключить JIT JavaScript", "Меньше поверхность атаки, медленнее сайты. JavaScript остаётся включённым. Действует на все сайты (в Gecko нет переключателя JIT по сайтам)", p.jitOff) { p.jitOff = it } }
        item { SwitchRow("DRM (Widevine)", "Нужен для некоторых видеосервисов", p.drm) { p.drm = it } }

        item { SectionHeader("Данные") }
        item { SwitchRow("Удалять данные сайтов при выходе", "Cookie, хранилища, кэш, разрешения (кнопка «Выход» или при следующем запуске)", p.clearOnExit) { p.clearOnExit = it } }
        item { SwitchRow("Приватные вкладки по умолчанию", "Новые вкладки — приватные сессии Gecko", p.incognitoDefault) { p.incognitoDefault = it } }
        item { SwitchRow("Запретить снимки экрана везде", "FLAG_SECURE для всего браузера (во вкладках «Банк» — всегда)", p.flagSecure) { p.flagSecure = it; b.updateWindowSecurity() } }
        item { SwitchRow("Вести историю посещений", "Выключено по умолчанию. В приватных вкладках и вкладках «Банк» история не записывается никогда", p.historyEnabled) { p.historyEnabled = it } }
        if (p.historyEnabled) item { ChoiceRow("Автоудаление истории", listOf(0 to "Не удалять", 1 to "Старше 1 дня", 7 to "Старше 7 дней", 30 to "Старше 30 дней", 90 to "Старше 90 дней"), p.historyDays) { p.historyDays = it; b.app.history.prune(it) } }
        item { NavRow("История", "${b.app.history.items.size} записей", Icons.Outlined.History) { b.backTo(Screen.HISTORY) } }
        item { NavRow("Удалить данные сайтов сейчас", icon = Icons.Outlined.LocalFireDepartment) { b.clearNow() } }

        item { SectionHeader("Разделы") }
        item { NavRow("Безопасность и режим оплаты", icon = Icons.Outlined.VerifiedUser) { b.backTo(Screen.SECURITY) } }
        item { NavRow("Дополнения", icon = Icons.Outlined.Extension) { b.backTo(Screen.ADDONS) } }
        item { NavRow("Обновления", "Тихо ${BuildConfig.VERSION_NAME}, GeckoView ${BuildConfig.GECKO_VERSION}", Icons.Outlined.SystemUpdate) { b.backTo(Screen.UPDATES) } }
        item { NavRow("О программе", icon = Icons.Outlined.Info) { b.backTo(Screen.ABOUT) } }
        item { NavRow("Сторонние компоненты и лицензии", "uBlock Origin, GeckoView, BouncyCastle, списки фильтров", Icons.Outlined.Description) { b.backTo(Screen.LICENSES) } }
    }
}

@Composable
fun SecurityScreen(b: Browser) {
    val p = b.prefs
    val a = b.app.addons
    SubScreen("Безопасность", { b.backTo() }) {
        item { SectionHeader("Режим оплаты") }
        item {
            Text("Сайты банков и платёжных систем (${b.app.payments.matcher.domains.size} доменов из подписанных списков + ваши) открываются во вкладке «Банк» " +
                "с отдельными cookie. На страницах оплаты в любой вкладке JavaScript сайта работает, но выключены все дополнения кроме доверенных " +
                "(закреплённых по id + версии + SHA-256), пользовательские скрипты и встроенные внедрения Тихо (Gecko extensions.quarantinedDomains / restrictedDomains). " +
                "Снимки экрана и оверлеи других приложений запрещены, касания при перекрытии окна игнорируются, защита от фингерпринтинга ослаблена (чтобы антифрод банка не блокировал платёж).",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        item {
            val trusted = a.trustedForPayment()
            ListItem(headlineContent = { Text("Доверенные на страницах оплаты") },
                supportingContent = { Text(if (trusted.isEmpty()) "Нет — на платёжных хостах не работает ни одно дополнение (restrictedDomains)"
                    else trusted.joinToString { id -> a.rows.firstOrNull { it.id == id }?.let { "${it.name} ${it.version}" } ?: if (id == AddonPolicy.TIHO_EXT_ID) "доверенные скрипты" else id }) })
        }
        if (p.paymentUserDomains.isNotEmpty()) item {
            ListItem(headlineContent = { Text("Добавленные вами платёжные сайты") }, supportingContent = { Text(p.paymentUserDomains.sorted().joinToString()) },
                trailingContent = { TextButton(onClick = { p.paymentUserDomains = emptySet(); b.app.payments.refresh(); b.app.engine.applyDynamicPrefs() }) { Text("Очистить") } })
        }
        item { SectionHeader("Защита") }
        item { SwitchRow("Предупреждать о фишинге и вредоносных сайтах", "Списки phishing-filter и URLhaus (из подписанных пакетов) + Safe Browsing Gecko", p.phishing) { p.phishing = it; b.app.threats.rebuild() } }
        item {
            Text("Всегда включено: предупреждение о подменных адресах (IDN/punycode — показываем xn--), белый список схем (file:, content:, javascript: не открываются), " +
                "подтверждение перед открытием внешних приложений, предупреждения при загрузке исполняемых файлов и загрузке по HTTP.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AddonsScreen(b: Browser) {
    val a = b.app.addons
    LaunchedEffect(Unit) { a.refresh() }
    var expanded by remember { mutableStateOf<String?>(null) }
    SubScreen("Дополнения", { b.backTo() }) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { b.backTo(Screen.AMO) }) { Text("Каталог AMO") }
                OutlinedButton(onClick = { b.backTo(); b.open("https://addons.mozilla.org/ru/android/", null) }) { Text("Сайт addons.mozilla.org") }
            }
        }
        if (a.rows.isEmpty()) item { Text("Нет установленных дополнений", Modifier.padding(16.dp)) }
        items(a.rows.toList(), key = { it.id }) { r ->
            AddonRow(b, a, r, expanded == r.id) { expanded = if (expanded == r.id) null else r.id }
        }
        item {
            Text("Установка только с addons.mozilla.org: файл загружается по HTTPS, его SHA-256 сверяется с AMO и закрепляется; подпись Mozilla проверяет Gecko. " +
                "Автообновления дополнений выключены — обновляйте кнопкой «Проверить обновление» (после этого доверие на страницах оплаты нужно подтвердить заново).",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AddonRow(b: Browser, a: Addons, r: Addons.Row, open: Boolean, toggle: () -> Unit) {
    Column {
        ListItem(
            leadingContent = { a.icons[r.id]?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.size(36.dp)) } ?: Icon(Icons.Outlined.Extension, null) },
            headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(buildString {
                    append(r.version)
                    if (r.recommended) append(" · рекомендовано Mozilla")
                    if (r.trusted) append(" · доверено на оплате")
                    if (r.trustBroken) append(" · доверие сброшено (версия/хэш изменились)")
                    if (r.appDisabled) append(" · приостановлено (открыта вкладка «Банк»)")
                }, style = MaterialTheme.typography.bodySmall)
            },
            trailingContent = { Switch(r.enabled, { a.setEnabled(r, it) }) },
            modifier = Modifier.clickable(onClick = toggle),
        )
        if (open) Column(Modifier.padding(start = 24.dp, end = 16.dp, bottom = 12.dp)) {
            if (r.description.isNotEmpty()) Text(r.description, style = MaterialTheme.typography.bodySmall)
            Text("ID: ${r.id}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("SHA-256 XPI: " + (r.pin?.sha256?.let { UserScriptPin.pretty(it) } ?: "не закреплён"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            SwitchRow("Работать в приватных вкладках", null, r.private) { a.setPrivate(r, it) }
            SwitchRow("Доверять на страницах оплаты", "Только эта версия с этим SHA-256; любое обновление сбрасывает доверие", r.trusted) { a.setTrust(r, it) }
            Text("Доступ к сайтам", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            val req = r.requiredOrigins
            Text(if (req.isEmpty()) "Обязательный доступ: нет" else "Обязательный доступ: " + req.joinToString { if (it == "<all_urls>" || it.startsWith("*://*/")) "все сайты" else it },
                style = MaterialTheme.typography.bodySmall)
            r.optionalOrigins.forEach { o -> SwitchRow(if (o == "<all_urls>" || o.startsWith("*://*/")) "Все сайты" else o, "Необязательный доступ", o in r.grantedOrigins) { a.setOrigin(r, o, it) } }
            if (r.optionalOrigins.isEmpty()) Text("Нет необязательных разрешений для сайтов (для MV2-дополнений доступ задаётся при установке; на страницах оплаты он всё равно отключён).", style = MaterialTheme.typography.bodySmall)
            Text("Разрешения: " + r.permissions.joinToString { permissionLabel(it) }.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                r.optionsUrl?.let { u -> OutlinedButton(onClick = { b.backTo(); b.newTab(u) }) { Text("Настройки") } }
                OutlinedButton(onClick = { a.checkUpdate(r) }) { Text("Проверить обновление") }
                TextButton(onClick = { a.uninstall(r) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
        }
        HorizontalDivider()
    }
}

@Composable
fun AmoScreen(b: Browser) {
    val a = b.app.addons
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AddonPolicy.AmoAddon>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    fun run(query: String?) { loading = true; error = null; a.search(query) { r, e -> loading = false; results = r; error = e } }
    LaunchedEffect(Unit) { run(null) }
    SubScreen("addons.mozilla.org", { b.backTo(Screen.ADDONS) }) {
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Поиск дополнений") },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { run(q) }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
                IconButton(onClick = { run(q) }) { Icon(Icons.Outlined.Search, "Искать") }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        error?.let { e -> item { Text("Ошибка: $e", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        if (q.isBlank() && results != null) item { SectionHeader("Рекомендуемые Mozilla для Android") }
        items(results.orEmpty(), key = { it.guid }) { r ->
            val installed = a.rows.firstOrNull { it.id == r.guid }
            ListItem(
                headlineContent = { Text(r.name) },
                supportingContent = { Text((if (r.recommended) "★ Рекомендовано · " else "") + "${r.users} польз. · ${r.version}\n${r.summary}", maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    if (installed != null && installed.version == r.version) Text("Установлено", style = MaterialTheme.typography.labelSmall)
                    else Button(onClick = { a.installFromAmo(r) }) { Text(if (installed != null) "Обновить" else "Установить") }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun UserscriptsScreen(b: Browser) {
    val ext = b.app.extensions
    var url by remember { mutableStateOf("") }
    val catalog = remember { ext.catalog(b.app.contentPacks.text("userscripts/catalog.json")) }
    SubScreen("Пользовательские скрипты", { b.backTo() }) {
        item {
            Text("Скрипты работают во встроенном WebExtension Тихо (изолированный мир, GM API). Устанавливаются только по HTTPS, код и @require закрепляются по SHA-256. " +
                "На страницах оплаты не работают, пока вы явно не доверите конкретную версию.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("https://…/script.user.js") })
                TextButton(onClick = { if (url.isNotBlank()) b.offerUserscript(url.trim()) }) { Text("Установить") }
            }
        }
        items(ext.scripts.toList(), key = { it.id }) { s ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + " " + s.version, fontWeight = FontWeight.Bold)
                        Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("SHA-256 " + UserScriptPin.pretty(s.sha256).take(39) + "…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        if (s.tampered) Text("Код изменён вне Тихо — скрипт отключён", color = MaterialTheme.colorScheme.error)
                    }
                    Switch(s.enabled, { ext.setEnabled(s.id, it) }, enabled = !s.tampered)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.runsOnPayment, { ext.setTrustedPayment(s.id, it) }); Text("Доверять на страницах оплаты", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { ext.delete(s.id) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider()
            }
        }
        if (catalog.isNotEmpty()) {
            item { SectionHeader("Каталог (из подписанного пакета)") }
            items(catalog) { c ->
                ListItem(headlineContent = { Text(c.name) }, supportingContent = { Text(c.description, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { TextButton(onClick = { b.offerUserscript(c.url) }) { Text("Установить") } })
            }
        }
    }
}

@Composable
fun BookmarksScreen(b: Browser) {
    SubScreen("Закладки", { b.backTo() }) {
        if (b.app.bookmarks.items.isEmpty()) item { Text("Закладок нет. Добавить: меню → ☆", Modifier.padding(16.dp)) }
        items(b.app.bookmarks.items.toList(), key = { it.url }) { bm ->
            ListItem(headlineContent = { Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(Homograph.display(try { Uri.parse(bm.url).host } catch (_: Exception) { null }), maxLines = 1) },
                trailingContent = { TextButton(onClick = { b.app.bookmarks.remove(bm) }) { Text("Удалить") } },
                modifier = Modifier.clickable { b.backTo(); b.open(bm.url, b.current.value) })
        }
    }
}

@Composable
fun DownloadsScreen(b: Browser) {
    SubScreen("Загрузки", { b.backTo() }, actions = { TextButton(onClick = { b.app.downloads.clear() }) { Text("Очистить") } }) {
        if (b.app.downloads.items.isEmpty()) item { Text("Загрузок нет", Modifier.padding(16.dp)) }
        items(b.app.downloads.items.toList(), key = { it.id }) { d ->
            ListItem(headlineContent = { Text(d.name) },
                supportingContent = { Text(d.url.take(80), maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.clickable { b.openDownload(d.name, d.uri, null) })
        }
    }
}

@Composable
fun HistoryScreen(b: Browser) {
    val h = b.app.history
    var q by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { h.prune(b.prefs.historyDays) }
    val list = remember(q, h.items.size, h.items.firstOrNull()) { h.search(q) }
    val fmt = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru")) }
    SubScreen("История", { b.backTo() }, actions = { TextButton(enabled = h.items.isNotEmpty(), onClick = { confirm = true }) { Text("Очистить") } }) {
        if (!b.prefs.historyEnabled) item { Text("Запись истории выключена (Настройки → Данные). Старые записи можно просмотреть или удалить.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("Поиск по истории") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        }
        if (list.isEmpty()) item { Text(if (q.isBlank()) "История пуста" else "Ничего не найдено", Modifier.padding(16.dp)) }
        items(list, key = { it.id }) { e ->
            ListItem(headlineContent = { Text(e.title.ifBlank { e.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(fmt.format(java.util.Date(e.time)) + " · " + e.url.take(90), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                trailingContent = { IconButton(onClick = { h.remove(e) }) { Icon(Icons.Outlined.Delete, "Удалить запись") } },
                modifier = Modifier.clickable { b.backTo(); b.navigate(e.url) })
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Очистить историю?") }, text = { Text("Все ${h.items.size} записей будут удалены без возможности восстановления.") },
        confirmButton = { TextButton(onClick = { h.clear(); confirm = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}

@Composable
fun UpdatesScreen(b: Browser) {
    val p = b.prefs
    val up = b.app.appUpdater
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    SubScreen("Обновления", { b.backTo() }) {
        item { SectionHeader("Браузер (APK)") }
        item {
            ListItem(headlineContent = { Text("Тихо ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                supportingContent = { Text("GeckoView ${BuildConfig.GECKO_VERSION}\nИсправления безопасности Gecko приходят только с новым APK — проверка ежедневно.\n" +
                    (p.appUpdateStatus.ifEmpty { "Ещё не проверялось" }) + (if (p.appLastCheck.isNotEmpty()) "\nПроверено: ${p.appLastCheck}" else "")) })
        }
        item {
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
        item { SwitchRow("Проверять обновления браузера ежедневно", "update-v2.json, подпись Ed25519; APK: SHA-256, размер и сертификат подписи должны совпасть", p.appUpdateCheck) { p.appUpdateCheck = it; org.ivanivorontsov.tiho.update.UpdateJob.schedule(ctx, p.contentAuto || it, p.contentWifiOnly) } }
        item { SectionHeader("Списки безопасности (подписанные пакеты)") }
        item {
            ListItem(headlineContent = { Text("Пакет v${b.app.contentPacks.version}") },
                supportingContent = { Text((p.contentStatus.ifEmpty { "Встроенные списки" }) + (if (p.contentLastCheck.isNotEmpty()) "\nПроверено: ${p.contentLastCheck}" else "") +
                    "\nФишинг/URLhaus, платёжные домены, каталог скриптов. Фильтры рекламы обновляет сам uBlock Origin.") })
        }
        item { Row(Modifier.padding(horizontal = 16.dp)) { OutlinedButton(onClick = { b.app.contentPacks.check(true) { s -> b.toast(s) } }) { Text("Обновить списки") } } }
        item { SwitchRow("Автообновление списков", null, p.contentAuto) { p.contentAuto = it; org.ivanivorontsov.tiho.update.UpdateJob.schedule(ctx, it, p.contentWifiOnly) } }
        item { SwitchRow("Только по Wi-Fi", null, p.contentWifiOnly) { p.contentWifiOnly = it; org.ivanivorontsov.tiho.update.UpdateJob.schedule(ctx, p.contentAuto, it) } }
        item { SectionHeader("Ключи подписи") }
        item {
            Text("primary: ${TrustedKeys.PRIMARY}\nbackup: ${TrustedKeys.BACKUP}\nci (только update-v2.json): ${TrustedKeys.CI}\nAPK: ${TrustedKeys.APK_CERT_SHA256}",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun AboutScreen(b: Browser) {
    val diag = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        listOf("privacy.resistFingerprinting", "privacy.fingerprintingProtection", "network.cookie.cookieBehavior", "dom.security.https_only_mode",
            "privacy.globalprivacycontrol.enabled", "media.peerconnection.ice.default_address_only", "network.trr.mode", "toolkit.telemetry.enabled",
            "datareporting.healthreport.uploadEnabled", "app.normandy.enabled", "network.prefetch-next", "network.http.referer.XOriginTrimmingPolicy",
            "extensions.quarantinedDomains.enabled", "javascript.options.ion").forEach { k -> b.app.engine.readPref(k) { v -> diag[k] = v } }
        b.app.engine.readPref("extensions.quarantinedDomains.list") { v -> diag["extensions.quarantinedDomains.list (хостов)"] = v.count { it == ',' }.plus(1).toString() }
    }
    SubScreen("О программе", { b.backTo() }) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Тихо ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                Text("Движок: Mozilla GeckoView ${BuildConfig.GECKO_VERSION} (готовый AAR из maven.mozilla.org)")
                Text("Без аналитики, без Google Play Services. Телеметрия, исследования (Normandy) и отчёты о сбоях Gecko выключены.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { NavRow("Сторонние компоненты и лицензии", "uBlock Origin, GeckoView, BouncyCastle, списки фильтров", Icons.Outlined.Description) { b.screen.value = Screen.LICENSES } }
        item { SectionHeader("Действующие настройки Gecko") }
        items(diag.entries.sortedBy { it.key }.map { it.key to it.value }) { (k, v) ->
            Text("$k = $v", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private class ThirdParty(val name: String, val version: String, val author: String, val license: String, val source: String, val asset: String)

private val THIRD_PARTY = listOf(
    ThirdParty("uBlock Origin", "1.75.0 (подписанный xpi с addons.mozilla.org, без изменений)", "Raymond Hill (gorhill) и участники", "GNU GPL v3", "https://github.com/gorhill/uBlock/tree/1.75.0", "GPL-3.0.txt"),
    ThirdParty("Mozilla GeckoView", BuildConfig.GECKO_VERSION, "Mozilla Foundation и участники", "Mozilla Public License 2.0", "https://hg.mozilla.org/mozilla-central/ , https://github.com/mozilla-firefox/firefox", "MPL-2.0.txt"),
    ThirdParty("Bouncy Castle (bcprov-jdk18on)", "1.78.1", "The Legion of the Bouncy Castle Inc.", "MIT (Bouncy Castle License)", "https://github.com/bcgit/bc-java", "BouncyCastle-MIT.txt"),
    ThirdParty("AndroidX, Jetpack Compose, Material 3", "Compose BOM 2026.09.00", "The Android Open Source Project", "Apache License 2.0", "https://android.googlesource.com/platform/frameworks/support", "Apache-2.0.txt"),
    ThirdParty("Kotlin stdlib, kotlinx.coroutines", "1.11.0", "JetBrains s.r.o. и участники", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines", "Apache-2.0.txt"),
    ThirdParty("Phishing URL Blocklist (встроенный список)", "2026-10-01", "malware-filter (curben); источники OpenPhish, IPThreat, PhishTank", "CC BY-SA 4.0; код — MIT", "https://gitlab.com/malware-filter/phishing-filter", "CC-BY-SA-4.0.txt"),
    ThirdParty("Online Malicious URL Blocklist (встроенный список)", "2026-10-01", "malware-filter (curben); данные URLhaus (abuse.ch)", "CC0 1.0 и MIT", "https://gitlab.com/malware-filter/urlhaus-filter", "CC0-1.0.txt"),
    ThirdParty("Списки фильтров из пакетов обновлений", "по пакету", "EasyList, RU AdList, uAssets, AdGuard, HaGeZi, Peter Lowe и др.", "GPLv3 / CC BY-SA 3.0 / CC BY 3.0 / MIT и др. (по таблице)", "https://github.com/ivanivorontsov-sudo/tiho-updates", "THIRD_PARTY_NOTICES-filters.md"),
)

private val LICENSE_FILES = listOf(
    "GPL-3.0.txt" to "GNU GPL v3", "MPL-2.0.txt" to "Mozilla Public License 2.0", "Apache-2.0.txt" to "Apache License 2.0",
    "BouncyCastle-MIT.txt" to "Bouncy Castle (MIT)", "MIT-malware-filter.txt" to "MIT (malware-filter)", "CC-BY-SA-4.0.txt" to "CC BY-SA 4.0",
    "CC0-1.0.txt" to "CC0 1.0", "THIRD_PARTY_NOTICES-filters.md" to "Авторы и лицензии списков фильтров",
)

@Composable
fun LicensesScreen(b: Browser) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    val text = remember(open) { open?.let { f -> runCatching { ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("") } }
    SubScreen("Сторонние компоненты и лицензии", { if (open != null) open = null else b.screen.value = Screen.ABOUT }) {
        if (open != null) {
            item { Text(text.orEmpty(), Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
            return@SubScreen
        }
        item {
            Text("Тихо распространяется по GNU GPL v3. В приложение входят компоненты других авторов; они используются без изменений на условиях своих лицензий. Исходный код каждого компонента доступен по ссылке.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
        items(THIRD_PARTY) { c ->
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = { Text("Версия: ${c.version}\n${c.author}\nЛицензия: ${c.license}\nИсходный код: ${c.source}") },
                modifier = Modifier.clickable { open = c.asset },
            )
        }
        item { SectionHeader("Полные тексты лицензий") }
        items(LICENSE_FILES) { (f, t) -> NavRow(t, icon = Icons.Outlined.Description) { open = f } }
    }
}
