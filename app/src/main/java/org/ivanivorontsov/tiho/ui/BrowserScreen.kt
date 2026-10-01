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
import org.ivanivorontsov.tiho.security.Homograph
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.graphics.toArgb

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
            Screen.Updates -> UpdatesScreen(b)
        }
        if (screen != Screen.Browser) BackHandler { b.screen.value = if (screen in setOf(Screen.Privacy, Screen.Filters, Screen.About, Screen.Updates)) Screen.Settings else Screen.Browser }
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
    var paySheet by remember { mutableStateOf(false) }
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
        AddressBar(b, tab, onShield = { shieldSheet = true }, onPayment = { paySheet = true })
        if (tab.loading.value) LinearProgressIndicator(progress = { tab.progress.floatValue }, modifier = Modifier.fillMaxWidth().height(2.dp))
        else Spacer(Modifier.height(2.dp))
        if (tab.safePayment) PaymentIndicator(tab) { paySheet = true }
        tab.payHint.value?.let { PayHintBanner(b, tab, it) }
        EngineBanner(b)
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
    if (paySheet) PaymentSheet(b, tab) { paySheet = false }
}

/**
 * Address bar: a platform EditText so IME_FLAG_NO_PERSONALIZED_LEARNING can be set (the keyboard must not learn
 * typed URLs / searches). Shows the committed URL only; suspicious IDN hosts are shown as punycode with a warning.
 */
@Composable
private fun AddressBar(b: Browser, tab: Tab, onShield: () -> Unit, onPayment: () -> Unit) {
    val url = tab.url.value
    var focused by remember { mutableStateOf(false) }
    var hasText by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<EditText?>(null) }
    val host = remember(url) { try { java.net.URI(url).host?.removePrefix("www.") } catch (_: Exception) { null } }
    val hg = tab.homograph.value
    val display = if (url.isEmpty()) "" else (host?.let { Homograph.display(it) } ?: url)
    val shieldsOn = b.prefs.adblock && !b.app.adblock.isAllowlisted(host)
    val fg = MaterialTheme.colorScheme.onSurface.toArgb()
    val hint = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Shield with blocked counter
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !tab.isHome) { if (tab.payment) onPayment() else onShield() }, contentAlignment = Alignment.Center) {
            BadgedBox(badge = {
                if (tab.blocked.intValue > 0 && shieldsOn) Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(if (tab.blocked.intValue > 999) "999+" else tab.blocked.intValue.toString(), fontSize = 10.sp)
                }
            }) {
                Icon(if (tab.payment) Icons.Filled.CreditCard else if (shieldsOn) Icons.Filled.Shield else Icons.Outlined.GppBad, "Защита",
                    tint = if (tab.payment) PayGreen else if (shieldsOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = if (tab.payment) PayGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.weight(1f).height(44.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                when {
                    hg != null && !focused -> Icon(Icons.Filled.Warning, "Подозрительный адрес", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    tab.payment -> Icon(Icons.Filled.Lock, "Безопасный режим оплаты", Modifier.size(16.dp), tint = PayGreen)
                    tab.incognito -> Icon(Icons.Outlined.VisibilityOff, "Инкогнито", Modifier.size(18.dp))
                    url.startsWith("https://") -> Icon(Icons.Filled.Lock, "Защищено", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    url.startsWith("http://") -> Icon(Icons.Outlined.LockOpen, "Не защищено", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                AndroidView(
                    factory = { c ->
                        EditText(c).apply {
                            background = null
                            setPadding(0, 0, 0, 0)
                            isSingleLine = true
                            textSize = 16f
                            this.hint = "Поиск или адрес"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_FULLSCREEN
                            setSelectAllOnFocus(true)
                            fun go(v: EditText) {
                                b.navigate(v.text.toString())
                                v.clearFocus()
                                (c.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(v.windowToken, 0)
                            }
                            setOnEditorActionListener { v, id, ev ->
                                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) { go(v as EditText); true } else false
                            }
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, st: Int, c0: Int, a: Int) {}
                                override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c0: Int) { hasText = !s.isNullOrEmpty() }
                                override fun afterTextChanged(s: android.text.Editable?) {}
                            })
                            edit = this
                        }
                    },
                    update = { et ->
                        et.setTextColor(fg); et.setHintTextColor(hint); et.highlightColor = (accent and 0x00ffffff) or 0x55000000
                        et.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, f ->
                            focused = f
                            val e = v as EditText
                            if (f) { e.setText(tab.url.value); e.selectAll() } else e.setText(if (tab.url.value.isEmpty()) "" else display)
                        }
                        if (!et.hasFocus() && et.text.toString() != display) et.setText(display)
                    },
                    modifier = Modifier.weight(1f),
                )
                if (focused && hasText) IconButton(onClick = { edit?.setText("") }, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Очистить", Modifier.size(18.dp)) }
            }
        }
        if (!tab.isHome) IconButton(onClick = { if (tab.loading.value) tab.stop() else tab.reload() }) {
            Icon(if (tab.loading.value) Icons.Filled.Close else Icons.Filled.Refresh, if (tab.loading.value) "Стоп" else "Обновить")
        }
    }
}

val PayGreen = Color(0xFF1B8A4B)

/** Native (not web-drawable) indicator: the page cannot fake or hide it. */
@Composable
private fun PaymentIndicator(tab: Tab, onClick: () -> Unit) {
    val strong = tab.payment
    Surface(color = if (strong) PayGreen else Color(0xFFB26A00), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (strong) Icons.Filled.VerifiedUser else Icons.Outlined.CreditCard, null, Modifier.size(18.dp), tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(if (strong) "Безопасный режим оплаты" + (if (tab.profileName != null) " · профиль «Банк»" else "")
                 else "Страница оплаты: скрипты Тихо отключены", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Info, "Подробнее", Modifier.size(18.dp), tint = Color.White)
        }
    }
}

@Composable
private fun PayHintBanner(b: Browser, tab: Tab, h: Tab.PayHint) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Похоже на оплату", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("На ${h.host} есть поля банковской карты. Открыть в «Безопасном режиме оплаты»? Сайт будет запомнен; " +
                "расширения, блокировка скриптов и подмена отпечатка там отключаются, JavaScript сайта работает.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { b.acceptPayHint(tab) }) { Text("Включить безопасный режим") }
                TextButton(onClick = { b.dismissPayHint(tab) }) { Text("Не сейчас") }
            }
        }
    }
}

/** «Движок устарел»: System WebView older than min_webview_major (built-in default, raised by signed packs). */
@Composable
private fun EngineBanner(b: Browser) {
    val e = b.engine.value
    val key = "${e.packageName}:${e.versionName}:${e.minMajor}"
    if (!(e.outdated || e.unknown) || b.prefs.webviewDismissed == key) return
    val ctx = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(if (e.unknown) "Не удалось определить движок WebView" else "Движок устарел", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Text(if (e.unknown) "Android не сообщает, какой WebView используется. Убедитесь, что Android System WebView или Chrome обновлены."
                 else "${e.packageName} ${e.versionName}: версия ${e.major} старше минимально безопасной ${e.minMajor}. В старом движке есть известные уязвимости — обновите его.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Row {
                if (e.packageName != null) TextButton(onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${e.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        b.newTab("https://play.google.com/store/apps/details?id=${e.packageName}")
                    }
                }) { Text("Обновить") }
                TextButton(onClick = { b.prefs.webviewDismissed = key }) { Text("Скрыть") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(b: Browser, tab: Tab, onDismiss: () -> Unit) {
    val host = try { java.net.URI(tab.url.value).host } catch (_: Exception) { null }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, null, tint = PayGreen); Spacer(Modifier.width(8.dp))
                Text(if (tab.payment) "Безопасный режим оплаты" else "Страница оплаты", style = MaterialTheme.typography.titleLarge)
            }
            Text(host ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val items = if (tab.payment) listOf(
                "JavaScript сайта работает как обычно",
                "Отключено всё наше: userscripts (кроме «доверенных на оплате» с совпадающим SHA-256), скриптлеты и косметика блокировщика, мост JS, шум отпечатка",
                "Настоящий User-Agent и часовой пояс (банки проверяют устройство)",
                "WebRTC — только через relay",
                "Профиль WebView: " + (tab.profileName?.let { "«Банк» (отдельные cookies и хранилище)" } ?: "общий (этот WebView не поддерживает профили)"),
                "Скриншоты запрещены, чужие окна поверх скрыты" + (if (android.os.Build.VERSION.SDK_INT >= 31) "" else " (скрытие окон — Android 12+)") + ", касания через перекрытие игнорируются",
                "Антифрод и 3-D Secure (ThreatMetrix, Cardinal, Arcot, ACS) не блокируются",
            ) else listOf(
                "Этот сайт в списке банков/оплаты: наши скрипты, шум отпечатка и userscripts здесь не запускаются",
                "Скриншоты запрещены, пока открыта страница",
                "Для полной изоляции (профиль «Банк», без моста JS) откройте её в отдельной вкладке",
            )
            items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(8.dp))
            if (tab.payment) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сторонние cookies в этой вкладке")
                        Text("Нужны некоторым окнам 3-D Secure", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(tab.thirdPartyCookies.value, { tab.setThirdPartyCookies(it) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onDismiss(); b.exitPaymentMode(tab) }) { Text("Выйти из режима оплаты") }
            } else {
                Button(onClick = { onDismiss(); b.openInPaymentTab(null, tab.url.value) }) { Text("Открыть во вкладке «Банк»") }
            }
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
                    if (!tab.isHome && !tab.payment) item("Открыть в режиме оплаты", Icons.Outlined.CreditCard) { b.openInPaymentTab(null, tab.url.value) }
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
                    item("Обновления", Icons.Outlined.SystemUpdate) { b.screen.value = Screen.Updates }
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
        b.app.appUpdater.available.value?.let { u ->
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().clickable { b.screen.value = Screen.Updates }) {
                Text("Доступна версия ${u.versionName} — нажмите, чтобы обновить", Modifier.padding(16.dp))
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
            is Tab.Warning.Phishing -> {
                Text("Опасный сайт", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("${w.host} есть в списках фишинга и вредоносных сайтов (malware-filter: phishing-filter, URLhaus). " +
                    "Такие страницы крадут пароли, данные карт или распространяют вирусы.")
                Spacer(Modifier.height(4.dp))
                Text("Совпадение: ${w.rule.take(80)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(20.dp))
                Row {
                    Button(onClick = { tab.warning.value = null; if (tab.webView.canGoBack()) tab.webView.goBack() else b.goHome() }) { Text("Уйти отсюда") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { tab.openPhishingAnyway(w) }) { Text("Всё равно открыть") }
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
            tab.homograph.value?.let { v -> Text("⚠ ${v.reason}. Адрес: ${v.ascii} (${v.unicode})", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
