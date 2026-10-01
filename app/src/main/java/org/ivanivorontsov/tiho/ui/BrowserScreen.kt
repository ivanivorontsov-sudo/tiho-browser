package org.ivanivorontsov.tiho.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.browser.Browser.Screen
import org.ivanivorontsov.tiho.browser.Tab
import org.ivanivorontsov.tiho.browser.UiDialog
import org.ivanivorontsov.tiho.security.Homograph
import org.mozilla.geckoview.GeckoView

val BankColor = Color(0xFF1B5E20)
val PrivateColor = Color(0xFF4A148C)

@Composable
fun BrowserApp(b: Browser) {
    val act = LocalContext.current as? MainActivity
    BackHandler { if (!b.back()) act?.moveTaskToBack(true) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrowserMain(b)
        if (b.screen.value != Screen.BROWSER) Surface(Modifier.fillMaxSize()) {
            when (b.screen.value) {
                Screen.TABS -> TabsScreen(b)
                Screen.SETTINGS -> SettingsScreen(b)
                Screen.ADDONS -> AddonsScreen(b)
                Screen.AMO -> AmoScreen(b)
                Screen.USERSCRIPTS -> UserscriptsScreen(b)
                Screen.BOOKMARKS -> BookmarksScreen(b)
                Screen.DOWNLOADS -> DownloadsScreen(b)
                Screen.UPDATES -> UpdatesScreen(b)
                Screen.SECURITY -> SecurityScreen(b)
                Screen.ABOUT -> AboutScreen(b)
                Screen.BROWSER -> {}
            }
        }
        b.dialogs.firstOrNull()?.let { DialogView(b, it) }
        b.app.addons.prompt.value?.let { AddonPromptView(b, it) }
        b.app.addons.busy.value?.let { msg ->
            Dialog(onDismissRequest = {}) { Card { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(16.dp)); Text(msg) } } }
        }
        b.popup.value?.let { (ext, s) ->
            Dialog(onDismissRequest = { b.closePopup() }) {
                Card(Modifier.fillMaxWidth().height(520.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ext.metaData.name ?: ext.id, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { b.closePopup() }) { Icon(Icons.Filled.Close, "Закрыть") }
                        }
                        AndroidView({ c -> GeckoView(c).also { it.setSession(s) } }, Modifier.fillMaxSize(), onRelease = { it.releaseSession() })
                    }
                }
            }
        }
        b.snack.value?.let { sn ->
            LaunchedEffect(sn) { kotlinx.coroutines.delay(4500); if (b.snack.value === sn) b.snack.value = null }
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp).navigationBarsPadding(),
                action = { if (sn.action != null) TextButton(onClick = { b.snack.value = null; sn.onAction?.invoke() }) { Text(sn.action) } }) { Text(sn.text) }
        }
    }
}

@Composable
private fun BrowserMain(b: Browser) {
    val tab = b.current.value
    Column(Modifier.fillMaxSize().then(if (b.fullscreen.value) Modifier else Modifier.systemBarsPadding().imePadding())) {
        if (!b.fullscreen.value && tab != null) {
            TopBar(b, tab)
            if (tab.loading.value) LinearProgressIndicator(progress = { tab.progress.floatValue }, Modifier.fillMaxWidth().height(2.dp))
            Banners(b, tab)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ c -> GeckoView(c).also { b.attachView(it) } }, Modifier.fillMaxSize(), update = { b.attachView(it) })
            if (tab != null && tab.isHome && tab.warning.value == null) HomePage(b, tab)
            (tab?.warning?.value as? Tab.Warning.Phishing)?.let { PhishingPage(b, tab, it) }
        }
        if (findOpen.value) FindBar(b)
    }
}

private val findOpen = mutableStateOf(false)

@Composable
private fun TopBar(b: Browser, tab: Tab) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(tab.id, tab.url.value, editing) { mutableStateOf(if (editing) tab.url.value else displayUrl(tab)) }
    var menu by remember { mutableStateOf(false) }
    val bg = when { tab.payment -> BankColor.copy(alpha = 0.18f); tab.incognito -> PrivateColor.copy(alpha = 0.18f); else -> MaterialTheme.colorScheme.surfaceVariant }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            tab.payment -> AssistChip(onClick = { b.screen.value = Screen.SECURITY }, label = { Text("Банк") }, leadingIcon = { Icon(Icons.Filled.CreditCard, null) })
            tab.incognito -> Icon(Icons.Outlined.VisibilityOff, "Приватная вкладка", Modifier.padding(6.dp))
            else -> Icon(if (tab.secure.value || tab.isHome) Icons.Filled.Lock else Icons.Outlined.LockOpen, if (tab.secure.value) "Защищённое соединение" else "Нет шифрования",
                Modifier.padding(6.dp), tint = if (tab.secure.value || tab.isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Поиск или адрес") },
            modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { b.navigate(text); editing = false }),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        if (tab.blocked.intValue > 0) BadgedBox(badge = { Badge { Text("${tab.blocked.intValue}") } }, Modifier.padding(horizontal = 6.dp)) {
            Icon(Icons.Filled.Shield, "Заблокировано трекеров", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { if (tab.loading.value) tab.stop() else tab.reload() }) { Icon(if (tab.loading.value) Icons.Filled.Close else Icons.Filled.Refresh, "Обновить") }
        OutlinedButton(onClick = { b.screen.value = Screen.TABS }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("${b.tabs.size}") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
            MainMenu(b, tab, menu) { menu = false }
        }
    }
}

private fun displayUrl(t: Tab): String {
    val u = t.url.value
    if (u.isEmpty()) return ""
    return try {
        val p = Uri.parse(u)
        val host = Homograph.display(p.host)
        if (p.scheme == "https" || p.scheme == "http") host + (p.path?.takeIf { it.length > 1 } ?: "") else u
    } catch (_: Exception) { u }
}

@Composable
private fun MainMenu(b: Browser, tab: Tab, open: Boolean, close: () -> Unit) {
    DropdownMenu(open, close) {
        Row {
            IconButton(onClick = { close(); tab.goBack() }, enabled = tab.canBack.value) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            IconButton(onClick = { close(); tab.goForward() }, enabled = tab.canForward.value) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд") }
            val bm = b.app.bookmarks.contains(tab.url.value)
            IconButton(onClick = { close(); if (!tab.isHome) b.app.bookmarks.toggle(tab.title.value.ifEmpty { tab.url.value }, tab.url.value) }) { Icon(if (bm) Icons.Filled.Star else Icons.Outlined.StarBorder, "Закладка") }
            IconButton(onClick = { close(); b.goHome() }) { Icon(Icons.Outlined.Home, "Домой") }
        }
        HorizontalDivider()
        DropdownMenuItem({ Text("Новая вкладка") }, { close(); b.newTab(incognito = false) }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
        DropdownMenuItem({ Text("Приватная вкладка") }, { close(); b.newTab(incognito = true) }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
        DropdownMenuItem({ Text("Вкладка «Банк»") }, { close(); if (!tab.isHome && !tab.payment) b.openInPaymentTab(tab.url.value) else b.newTab(incognito = false, payment = true) }, leadingIcon = { Icon(Icons.Outlined.CreditCard, null) })
        // Toolbar actions of installed extensions (uBlock Origin panel etc.)
        for ((id, a) in b.app.addons.actions) {
            val row = b.app.addons.rows.firstOrNull { it.id == id } ?: continue
            if (!row.enabled) continue
            DropdownMenuItem({ Text(a.title ?: row.name) }, { close(); a.click() }, leadingIcon = {
                b.app.addons.icons[id]?.let { Image(it.asImageBitmap()) } ?: Icon(Icons.Outlined.Extension, null)
            }, trailingIcon = { a.badgeText?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall) } })
        }
        DropdownMenuItem({ Text("Дополнения") }, { close(); b.screen.value = Screen.ADDONS }, leadingIcon = { Icon(Icons.Outlined.Extension, null) })
        DropdownMenuItem({ Text("Пользовательские скрипты") }, { close(); b.screen.value = Screen.USERSCRIPTS }, leadingIcon = { Icon(Icons.Outlined.Code, null) })
        DropdownMenuItem({ Text("Закладки") }, { close(); b.screen.value = Screen.BOOKMARKS }, leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) })
        DropdownMenuItem({ Text("Загрузки") }, { close(); b.screen.value = Screen.DOWNLOADS }, leadingIcon = { Icon(Icons.Outlined.Download, null) })
        DropdownMenuItem({ Text("Найти на странице") }, { close(); findOpen.value = true }, leadingIcon = { Icon(Icons.Outlined.FindInPage, null) })
        DropdownMenuItem({ Text("Версия для ПК") }, { close(); tab.setDesktop(!tab.desktop.value) }, leadingIcon = { Icon(Icons.Outlined.Computer, null) },
            trailingIcon = { Checkbox(tab.desktop.value, null) })
        DropdownMenuItem({ Text("Поделиться") }, { close(); if (!tab.isHome) b.share(tab.url.value) }, leadingIcon = { Icon(Icons.Outlined.Share, null) })
        DropdownMenuItem({ Text("Безопасность") }, { close(); b.screen.value = Screen.SECURITY }, leadingIcon = { Icon(Icons.Filled.VerifiedUser, null) })
        DropdownMenuItem({ Text("Настройки") }, { close(); b.screen.value = Screen.SETTINGS }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
        DropdownMenuItem({ Text("Удалить данные сайтов") }, { close(); b.clearNow() }, leadingIcon = { Icon(Icons.Outlined.LocalFireDepartment, null) })
        DropdownMenuItem({ Text("Выход") }, { close(); (b.activity as? MainActivity)?.exitApp() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) })
    }
}

@Composable
private fun Image(bm: androidx.compose.ui.graphics.ImageBitmap) =
    androidx.compose.foundation.Image(bm, null, Modifier.size(24.dp))

@Composable
private fun Banner(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        actions()
    }
}

@Composable
private fun Banners(b: Browser, tab: Tab) {
    if (tab.safePayment) {
        val trusted = b.app.addons.trustedForPayment().size
        Banner(BankColor, Icons.Filled.CreditCard,
            (if (tab.payment) "Вкладка «Банк»: отдельные cookie. " else "Страница оплаты. ") +
                "Дополнения и скрипты выключены" + (if (trusted > 0) " (кроме доверенных: $trusted)" else "") + ", снимки экрана и чужие оверлеи запрещены. JavaScript сайта работает.")
    }
    tab.homograph.value?.let { v ->
        Banner(MaterialTheme.colorScheme.error, Icons.Filled.Warning, "Адрес похож на другой сайт: ${v.ascii}. ${v.reason}")
    }
    tab.payHint.value?.let { h ->
        Banner(BankColor, Icons.Outlined.CreditCard, "Похоже на страницу оплаты (${h.host}). Открыть в защищённой вкладке «Банк»?") {
            TextButton(onClick = { b.acceptPayHint(tab) }) { Text("Открыть") }
            TextButton(onClick = { tab.dismissPayHint() }) { Text("Нет") }
        }
    }
    val up = b.app.appUpdater.available.value
    if (up != null && b.prefs.updateBannerDismissed != up.versionName) {
        Banner(MaterialTheme.colorScheme.primary, Icons.Outlined.SystemUpdate,
            "Доступно обновление Тихо ${up.versionName}" + (if (up.geckoVersion.isNotEmpty()) " (GeckoView ${up.geckoVersion.substringBefore('.')})" else "") + " — исправления безопасности движка") {
            TextButton(onClick = { b.screen.value = Screen.UPDATES }) { Text("Обновить") }
            IconButton(onClick = { b.prefs.updateBannerDismissed = up.versionName }) { Icon(Icons.Filled.Close, "Скрыть") }
        }
    } else if (b.prefs.appUpdateCheck && b.app.appUpdater.stale()) {
        Banner(MaterialTheme.colorScheme.error, Icons.Outlined.GppMaybe, "Более 14 дней не удаётся проверить обновления браузера — движок может быть устаревшим") {
            TextButton(onClick = { b.screen.value = Screen.UPDATES }) { Text("Проверить") }
        }
    }
}

@Composable
private fun HomePage(b: Browser, tab: Tab) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(when { tab.payment -> "Банк"; tab.incognito -> "Приватная вкладка"; else -> "Тихо" }, style = MaterialTheme.typography.headlineLarge,
            color = when { tab.payment -> BankColor; tab.incognito -> PrivateColor; else -> MaterialTheme.colorScheme.primary })
        Text(when {
            tab.payment -> "Отдельные cookie, дополнения (кроме доверенных) и скрипты выключены, снимки экрана запрещены."
            tab.incognito -> "История, cookie и кэш этой вкладки не сохраняются на диск."
            else -> "GeckoView ${org.ivanivorontsov.tiho.BuildConfig.GECKO_VERSION.substringBefore('.')} · uBlock Origin · строгая защита от слежки"
        }, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        val bms = b.app.bookmarks.items
        if (bms.isNotEmpty()) {
            Text("Закладки", style = MaterialTheme.typography.titleMedium)
            bms.take(12).forEach { bm ->
                ListItem(headlineContent = { Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(Homograph.display(try { Uri.parse(bm.url).host } catch (_: Exception) { null }), maxLines = 1) },
                    modifier = Modifier.clickable { b.open(bm.url, tab) })
            }
        }
    }
}

@Composable
private fun PhishingPage(b: Browser, tab: Tab, w: Tab.Warning.Phishing) {
    Column(Modifier.fillMaxSize().background(Color(0xFFB71C1C)).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Icon(Icons.Outlined.GppBad, null, tint = Color.White, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text("Опасный сайт", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("${Homograph.display(w.host)} есть в списках фишинга или вредоносных сайтов (правило: ${w.rule}). " +
            "Такие сайты крадут пароли и данные карт или заражают устройство.", color = Color.White)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { tab.warning.value = null; if (tab.canBack.value) tab.goBack() else b.goHome() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C))) { Text("Уйти отсюда") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { tab.openPhishingAnyway() }) { Text("Всё равно открыть (небезопасно)", color = Color.White) }
    }
}

@Composable
private fun FindBar(b: Browser) {
    var q by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it; b.find(it) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Найти на странице") })
        b.findResult.value?.let { Text(it, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall) }
        IconButton(onClick = { b.find(q, true) }) { Icon(Icons.Filled.KeyboardArrowUp, "Назад") }
        IconButton(onClick = { b.find(q) }) { Icon(Icons.Filled.KeyboardArrowDown, "Далее") }
        IconButton(onClick = { b.clearFind(); findOpen.value = false }) { Icon(Icons.Filled.Close, "Закрыть") }
    }
}

@Composable
private fun TabsScreen(b: Browser) {
    SubScreen("Вкладки (${b.tabs.size})", { b.screen.value = Screen.BROWSER }, actions = {
        TextButton(onClick = { b.closeAll() }) { Text("Закрыть все") }
    }) {
        item {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { b.newTab(incognito = false) }) { Text("Новая") }
                OutlinedButton(onClick = { b.newTab(incognito = true) }) { Text("Приватная") }
                OutlinedButton(onClick = { b.newTab(incognito = false, payment = true) }) { Text("Банк") }
            }
        }
        items(b.tabs.toList(), key = { it.id }) { t ->
            ListItem(
                headlineContent = { Text(t.title.value.ifEmpty { if (t.isHome) "Новая вкладка" else t.url.value }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (t === b.current.value) FontWeight.Bold else null) },
                supportingContent = { Text((if (t.payment) "Банк · " else if (t.incognito) "Приватная · " else "") + displayUrl(t), maxLines = 1) },
                leadingContent = { Icon(when { t.payment -> Icons.Outlined.CreditCard; t.incognito -> Icons.Outlined.VisibilityOff; else -> Icons.Outlined.Public }, null) },
                trailingContent = { IconButton(onClick = { b.closeTab(t) }) { Icon(Icons.Filled.Close, "Закрыть") } },
                modifier = Modifier.clickable { b.select(t) },
            )
        }
    }
}

@Composable
private fun DialogView(b: Browser, d: UiDialog) {
    when (d) {
        is UiDialog.Message -> AlertDialog(
            onDismissRequest = { b.dismiss(d); d.onResult(false) },
            title = { Text(d.title) },
            text = { Text(d.text, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { b.dismiss(d); d.onResult(true) }) { Text(d.ok, color = if (d.danger) MaterialTheme.colorScheme.error else Color.Unspecified) } },
            dismissButton = d.cancel?.let { c -> { TextButton(onClick = { b.dismiss(d); d.onResult(false) }) { Text(c) } } },
        )
        is UiDialog.Input -> {
            val values = remember(d) { d.fields.map { mutableStateOf(it.initial) } }
            AlertDialog(
                onDismissRequest = { b.dismiss(d); d.onResult(null) },
                title = { Text(d.title) },
                text = {
                    Column {
                        if (d.text.isNotEmpty()) Text(d.text)
                        d.fields.forEachIndexed { i, f ->
                            OutlinedTextField(values[i].value, { values[i].value = it }, label = { if (f.label.isNotEmpty()) Text(f.label) }, singleLine = true,
                                visualTransformation = if (f.password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { b.dismiss(d); d.onResult(values.map { it.value }) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { b.dismiss(d); d.onResult(null) }) { Text("Отмена") } },
            )
        }
        is UiDialog.Choice -> {
            val sel = remember(d) { mutableStateListOf<String>().also { it.addAll(d.selected) } }
            AlertDialog(
                onDismissRequest = { b.dismiss(d); d.onResult(null) },
                title = { Text(d.title) },
                text = {
                    LazyColumn {
                        items(d.options) { (id, label, enabled) ->
                            if (id.isEmpty()) Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                            else Row(Modifier.fillMaxWidth().clickable(enabled) {
                                if (d.multiple) { if (id in sel) sel.remove(id) else sel.add(id) } else { b.dismiss(d); d.onResult(listOf(id)) }
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (d.multiple) Checkbox(id in sel, null) else RadioButton(id in sel, null)
                                Spacer(Modifier.width(8.dp)); Text(label)
                            }
                        }
                    }
                },
                confirmButton = { if (d.multiple) TextButton(onClick = { b.dismiss(d); d.onResult(sel.toList()) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { b.dismiss(d); d.onResult(null) }) { Text("Отмена") } },
            )
        }
        is UiDialog.Menu -> AlertDialog(
            onDismissRequest = { b.dismiss(d); d.onDismiss() },
            title = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Column { d.items.forEach { (label, act) -> Text(label, Modifier.fillMaxWidth().clickable { b.dismiss(d); act() }.padding(vertical = 12.dp)) } } },
            confirmButton = {},
        )
    }
}

@Composable
private fun AddonPromptView(b: Browser, p: org.ivanivorontsov.tiho.gecko.Addons.Prompt) {
    var priv by remember(p) { mutableStateOf(false) }
    val done = { ok: Boolean -> b.app.addons.prompt.value = null; p.onResult(ok, priv) }
    AlertDialog(
        onDismissRequest = { done(false) },
        title = { Text(p.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (p.text.isNotEmpty()) Text(p.text)
                if (p.permissions.isNotEmpty() || p.origins.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp)); Text("Разрешения:", fontWeight = FontWeight.Bold)
                    (p.permissions.map { permissionLabel(it) } + p.origins.map { if (it == "<all_urls>" || it.startsWith("*://*/")) "Доступ к данным на всех сайтах" else "Доступ к $it" })
                        .distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (p.showPrivate) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { priv = !priv }) {
                    Checkbox(priv, { priv = it }); Text("Работать в приватных вкладках")
                }
            }
        },
        confirmButton = { TextButton(onClick = { done(true) }) { Text("Разрешить") } },
        dismissButton = { TextButton(onClick = { done(false) }) { Text("Отмена") } },
    )
}

fun permissionLabel(p: String): String = when (p) {
    "tabs" -> "Доступ к вкладкам браузера"; "storage", "unlimitedStorage" -> "Хранение данных"
    "webRequest", "webRequestBlocking", "webRequestFilterResponse" -> "Перехват и блокировка сетевых запросов"
    "cookies" -> "Доступ к cookie"; "history" -> "Доступ к истории"; "downloads" -> "Управление загрузками"
    "privacy" -> "Чтение и изменение настроек приватности"; "dns" -> "DNS-запросы"; "menus", "contextMenus" -> "Пункты контекстного меню"
    "scripting" -> "Внедрение скриптов на страницы"; "clipboardWrite" -> "Запись в буфер обмена"; "clipboardRead" -> "Чтение буфера обмена"
    "nativeMessaging" -> "Обмен сообщениями с приложениями"; "notifications" -> "Уведомления"; "management" -> "Управление дополнениями"
    else -> p
}
