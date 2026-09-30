package org.ivanivorontsov.tiho.ui

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.browser.Screen
import org.ivanivorontsov.tiho.browser.Tab
import org.ivanivorontsov.tiho.privacy.UrlCleaner

@Composable
fun BrowserApp(b: Browser) {
    val screen by b.screen
    val fs by b.fullscreen
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (screen) {
            Screen.Browser -> BrowserScreen(b)
            Screen.Tabs -> TabsScreen(b)
            Screen.Bookmarks -> BookmarksScreen(b)
            Screen.Downloads -> DownloadsScreen(b)
            Screen.Settings -> SettingsScreen(b)
            Screen.Privacy -> PrivacyScreen(b)
            Screen.Filters -> FiltersScreen(b)
            Screen.Extensions -> ExtensionsScreen(b)
            Screen.About -> AboutScreen(b)
        }
        if (screen != Screen.Browser) BackHandler { b.screen.value = if (screen in setOf(Screen.Privacy, Screen.Filters, Screen.About)) Screen.Settings else Screen.Browser }
        fs?.let { v ->
            BackHandler { b.exitFullscreen() }
            AndroidView(factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                update = { f -> if (v.parent !== f) { (v.parent as? ViewGroup)?.removeView(v); f.removeAllViews(); f.addView(v, FrameLayout.LayoutParams(-1, -1)) } },
                modifier = Modifier.fillMaxSize())
        }
        b.dialog.value?.let { d ->
            AlertDialog(
                onDismissRequest = { b.dialog.value = null; d.onDismiss() },
                title = { Text(d.title) },
                text = { Text(d.text) },
                confirmButton = { TextButton(onClick = { b.dialog.value = null; d.onConfirm() }) { Text(d.confirm) } },
                dismissButton = { TextButton(onClick = { b.dialog.value = null; d.onDismiss() }) { Text(d.dismiss) } },
            )
        }
        SnackHost(b, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).navigationBarsPadding())
    }
}

@Composable
private fun SnackHost(b: Browser, modifier: Modifier) {
    val host = remember { SnackbarHostState() }
    val s = b.snack.value
    LaunchedEffect(s?.id) {
        if (s != null) {
            val r = host.showSnackbar(s.text, s.action, withDismissAction = s.action == null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) s.onAction?.invoke()
            b.snack.value = null
        }
    }
    SnackbarHost(host, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(b: Browser) {
    val tab = b.current.value ?: return
    val ctx = LocalContext.current
    var findMode by remember { mutableStateOf(false) }
    var shieldSheet by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    BackHandler {
        when {
            findMode -> { findMode = false; tab.webView.clearMatches() }
            tab.warning.value != null -> { tab.warning.value = null; if (tab.webView.canGoBack()) tab.webView.goBack() else b.goHome() }
            tab.webView.canGoBack() -> tab.webView.goBack()
            !tab.isHome -> b.goHome()
            b.tabs.size > 1 -> b.closeTab(tab)
            else -> (ctx as? MainActivity)?.exitApp()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        AddressBar(b, tab, onShield = { shieldSheet = true })
        if (tab.loading.value) LinearProgressIndicator(progress = { tab.progress.floatValue }, modifier = Modifier.fillMaxWidth().height(2.dp))
        else Spacer(Modifier.height(2.dp))
        AnimatedVisibility(findMode) { FindBar(b, tab) { findMode = false; tab.webView.clearMatches() } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(tab.id) {
                AndroidView(
                    factory = { c -> FrameLayout(c) },
                    update = { f ->
                        val wv = tab.webView
                        if (wv.parent !== f) { (wv.parent as? ViewGroup)?.removeView(wv); f.removeAllViews(); f.addView(wv, FrameLayout.LayoutParams(-1, -1)) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (tab.isHome) HomeScreen(b)
            tab.warning.value?.let { WarningOverlay(b, tab, it) }
        }
        BottomBar(b, tab, onFind = { findMode = true; focus.clearFocus() })
    }
    if (shieldSheet) ShieldSheet(b, tab) { shieldSheet = false }
}

@Composable
private fun AddressBar(b: Browser, tab: Tab, onShield: () -> Unit) {
    val url = tab.url.value
    var focused by remember { mutableStateOf(false) }
    var field by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
    val focus = LocalFocusManager.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(url, focused) {
        if (!focused) field = TextFieldValue(url)
        else {
            // Select the whole address after the tap has placed the cursor (Chrome-like: typing replaces it).
            kotlinx.coroutines.delay(60)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    val host = remember(url) { try { java.net.URI(url).host?.removePrefix("www.") } catch (_: Exception) { null } }
    val shieldsOn = b.prefs.adblock && !b.app.adblock.isAllowlisted(host)

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Shield with blocked counter
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !tab.isHome) { onShield() }, contentAlignment = Alignment.Center) {
            BadgedBox(badge = {
                if (tab.blocked.intValue > 0 && shieldsOn) Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(if (tab.blocked.intValue > 999) "999+" else tab.blocked.intValue.toString(), fontSize = 10.sp)
                }
            }) {
                Icon(if (shieldsOn) Icons.Filled.Shield else Icons.Outlined.GppBad, "Защита",
                    tint = if (shieldsOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.weight(1f).height(44.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                when {
                    tab.incognito -> Icon(Icons.Outlined.VisibilityOff, "Инкогнито", Modifier.size(18.dp))
                    url.startsWith("https://") -> Icon(Icons.Filled.Lock, "Защищено", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    url.startsWith("http://") -> Icon(Icons.Outlined.LockOpen, "Не защищено", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = if (focused) field else TextFieldValue(if (url.isEmpty()) "" else (host ?: url)),
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { b.navigate(field.text); focus.clearFocus() }),
                    modifier = Modifier.weight(1f).focusRequester(fr).onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) field = TextFieldValue(url, TextRange(0, url.length))
                    },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (!focused && url.isEmpty() || focused && field.text.isEmpty()) Text("Поиск или адрес", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            inner()
                        }
                    },
                )
                if (focused && field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue("") }, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Очистить", Modifier.size(18.dp)) }
            }
        }
        if (!tab.isHome) IconButton(onClick = { if (tab.loading.value) tab.stop() else tab.reload() }) {
            Icon(if (tab.loading.value) Icons.Filled.Close else Icons.Filled.Refresh, if (tab.loading.value) "Стоп" else "Обновить")
        }
    }
}

@Composable
private fun FindBar(b: Browser, tab: Tab, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it; tab.webView.findAllAsync(it) }, Modifier.weight(1f).focusRequester(fr), singleLine = true,
            placeholder = { Text("Найти на странице") }, trailingIcon = { Text(b.findResult.value, style = MaterialTheme.typography.labelMedium) })
        IconButton(onClick = { tab.webView.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Назад") }
        IconButton(onClick = { tab.webView.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Далее") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть") }
    }
}

@Composable
private fun BottomBar(b: Browser, tab: Tab, onFind: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { tab.webView.goBack() }, enabled = tab.canBack.value && !tab.isHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            IconButton(onClick = { tab.webView.goForward() }, enabled = tab.canForward.value) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд") }
            IconButton(onClick = { b.goHome() }) { Icon(Icons.Outlined.Home, "Домой") }
            IconButton(onClick = { b.screen.value = Screen.Tabs }) {
                Box(Modifier.size(24.dp).background(Color.Transparent, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(5.dp), border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), color = Color.Transparent, modifier = Modifier.size(22.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(b.tabs.size.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                DropdownMenu(menu, { menu = false }) {
                    val item = @Composable { text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit ->
                        DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; action() })
                    }
                    item("Новая вкладка", Icons.Outlined.Add) { b.newTab() }
                    item("Новая инкогнито-вкладка", Icons.Outlined.VisibilityOff) { b.newTab(incognito = true) }
                    if (!tab.isHome) {
                        val bm = b.app.bookmarks.contains(tab.url.value)
                        item(if (bm) "Удалить из закладок" else "Добавить в закладки", if (bm) Icons.Filled.Star else Icons.Outlined.StarBorder) {
                            b.app.bookmarks.toggle(tab.title.value, tab.url.value)
                        }
                    }
                    item("Закладки", Icons.Outlined.Bookmarks) { b.screen.value = Screen.Bookmarks }
                    item("Загрузки", Icons.Outlined.Download) { b.screen.value = Screen.Downloads }
                    if (!tab.isHome) {
                        item("Найти на странице", Icons.Outlined.FindInPage, onFind)
                        DropdownMenuItem(text = { Text("Версия для ПК") }, leadingIcon = { Icon(Icons.Outlined.Computer, null) },
                            trailingIcon = { Checkbox(tab.desktop.value, null) }, onClick = { menu = false; tab.setDesktop(!tab.desktop.value) })
                        item("Поделиться", Icons.Outlined.Share) {
                            val clean = UrlCleaner.clean(tab.url.value)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, clean), "Поделиться"))
                        }
                    }
                    item("Расширения", Icons.Outlined.Extension) { b.screen.value = Screen.Extensions }
                    item("Настройки", Icons.Outlined.Settings) { b.screen.value = Screen.Settings }
                    item("Очистить данные сейчас", Icons.Outlined.LocalFireDepartment) { b.clearNow() }
                    item("Выход", Icons.AutoMirrored.Filled.ExitToApp) { (ctx as? MainActivity)?.exitApp() }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(b: Browser) {
    val bm = b.app.bookmarks.items
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Тихо", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("приватный браузер", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Заблокировано рекламы и трекеров", style = MaterialTheme.typography.labelLarge)
                Text("%,d".format(b.prefs.totalBlocked).replace(',', ' '), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                val p = b.prefs
                Text(buildString {
                    append(if (p.clearOnExit) "Данные стираются при выходе" else "Данные сохраняются")
                    append(" · "); append(if (p.httpsOnly) "только HTTPS" else "HTTP разрешён")
                    append(" · "); append(if (p.antiFp) "защита от отпечатков" else "без защиты от отпечатков")
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Закладки", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(bm.take(12)) { m ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { b.current.value?.load(m.url) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val host = try { java.net.URI(m.url).host?.removePrefix("www.") ?: m.url } catch (_: Exception) { m.url }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(host.take(1).uppercase(), style = MaterialTheme.typography.titleLarge) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(m.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun WarningOverlay(b: Browser, tab: Tab, w: Tab.Warning) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.GppMaybe, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        when (w) {
            is Tab.Warning.NoHttps -> {
                Text("Защищённое соединение недоступно", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Сайт ${w.host} не открылся по HTTPS. Включён режим «Только HTTPS». Если продолжить по HTTP, " +
                    "провайдер и любой посредник смогут видеть и изменять страницу.")
                Spacer(Modifier.height(20.dp))
                Row {
                    Button(onClick = { tab.warning.value = null; if (tab.webView.canGoBack()) tab.webView.goBack() else b.goHome() }) { Text("Назад") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { tab.loadHttpAnyway(w.httpUrl) }) { Text("Открыть по HTTP") }
                }
            }
            is Tab.Warning.Ssl -> {
                Text("Небезопасное соединение", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Сертификат сайта не прошёл проверку: ${w.reason}. Тихо не открывает такие страницы — это может быть перехват трафика.")
                Spacer(Modifier.height(20.dp))
                Button(onClick = { tab.warning.value = null; if (tab.webView.canGoBack()) tab.webView.goBack() else b.goHome() }) { Text("Назад") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShieldSheet(b: Browser, tab: Tab, onDismiss: () -> Unit) {
    val host = try { java.net.URI(tab.url.value).host } catch (_: Exception) { null } ?: return onDismiss()
    val site = UrlCleaner.site(host)
    var allow by remember { mutableStateOf(b.app.adblock.isAllowlisted(host)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(site, style = MaterialTheme.typography.titleLarge)
            Text(if (tab.secure.value) "Соединение защищено (HTTPS)" else "Соединение НЕ защищено", color = if (tab.secure.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text("Заблокировано на странице: ${tab.blocked.intValue}", style = MaterialTheme.typography.titleMedium)
            val top = tab.blockedHosts.entries.sortedByDescending { it.value }.take(8)
            top.forEach { (h, n) -> Text("• $h — $n", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Защита на этом сайте")
                    Text("Блокировка рекламы, трекеров и cookie-баннеров", style = MaterialTheme.typography.bodySmall)
                }
                Switch(!allow, { on ->
                    allow = !on
                    b.prefs.allowlist = if (on) b.prefs.allowlist - site else b.prefs.allowlist + site
                    tab.resetScriptlets(); tab.reload()
                })
            }
            Spacer(Modifier.height(8.dp))
            Text("Сторонние cookies: ${if (b.prefs.thirdPartyCookies) "разрешены" else "заблокированы"} · Отпечаток: ${if (b.prefs.antiFp) "защищён" else "не защищён"} · WebRTC: ${arrayOf("выключен", "только relay", "включён")[b.prefs.webrtcMode]}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
