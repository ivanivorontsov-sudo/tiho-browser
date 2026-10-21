package org.ivanivorontsov.tiho.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ivanivorontsov.tiho.browser.Browser
import org.ivanivorontsov.tiho.browser.Tab
import org.ivanivorontsov.tiho.data.QuickLink
import org.ivanivorontsov.tiho.data.QuickLinks
import kotlin.math.roundToInt

private const val COLS = 4

/** Home-page tiles. In incognito tabs they are read-only: nothing new is written to disk from a private tab. */
@Composable
internal fun QuickLinksGrid(b: Browser, tab: Tab) {
    val ql = b.app.quickLinks
    val readOnly = tab.incognito
    var editing by remember { mutableStateOf<QuickLink?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<QuickLink?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    val items = ql.items
    if (readOnly && items.isEmpty()) return
    Text("Быстрые ссылки", style = MaterialTheme.typography.titleMedium)
    if (readOnly) Text("В приватной вкладке плитки нельзя менять", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))

    val total = items.size + if (readOnly) 0 else 1
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val cellW = maxWidth / COLS
        val cellH = 96.dp
        val cwPx = with(density) { cellW.toPx() }; val chPx = with(density) { cellH.toPx() }
        val rows = (total + COLS - 1) / COLS
        Box(Modifier.fillMaxWidth().height(cellH * rows.coerceAtLeast(1))) {
            fun slot(i: Int) = IntOffset(((i % COLS) * cwPx).roundToInt(), ((i / COLS) * chPx).roundToInt())
            items.forEachIndexed { index, q ->
                key(q.id) {
                    val dragging = dragId == q.id
                    val target = slot(index)
                    val anim by animateIntOffsetAsState(target, label = "tile")
                    val off = if (dragging) IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt()) else anim
                    var moved by remember { mutableStateOf(0f) }
                    val curQ by rememberUpdatedState(q)
                    val curTab by rememberUpdatedState(tab)
                    Box(Modifier.offset { off }.size(cellW, cellH).zIndex(if (dragging) 1f else 0f)
                        .pointerInput(q.id) { detectTapGestures(onTap = { if (dragId == null) b.open(curQ.url, curTab) }) }
                        .then(if (readOnly) Modifier else Modifier.pointerInput(q.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragId = q.id; moved = 0f; dragPos = slot(ql.items.indexOfFirst { it.id == q.id }).let { Offset(it.x.toFloat(), it.y.toFloat()) } },
                                onDrag = { ch, d ->
                                    ch.consume(); moved += d.getDistance(); dragPos += d
                                    val cur = ql.items.indexOfFirst { it.id == q.id }
                                    val col = ((dragPos.x + cwPx / 2) / cwPx).toInt().coerceIn(0, COLS - 1)
                                    val row = ((dragPos.y + chPx / 2) / chPx).toInt().coerceAtLeast(0)
                                    val to = (row * COLS + col).coerceIn(0, ql.items.size - 1)
                                    if (cur >= 0 && to != cur) ql.move(cur, to)
                                },
                                onDragEnd = { dragId = null; if (moved < 12f) menuFor = q.id else ql.commitOrder() },
                                onDragCancel = { dragId = null; ql.commitOrder() },
                            )
                        })) {
                        Tile(ql, q, lifted = dragging)
                        DropdownMenu(menuFor == q.id, { menuFor = null }) {
                            DropdownMenuItem({ Text("Изменить") }, { menuFor = null; editing = q }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                            DropdownMenuItem({ Text("Удалить") }, { menuFor = null; deleting = q }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                        }
                    }
                }
            }
            if (!readOnly) {
                val s = slot(items.size)
                Column(Modifier.offset { s }.size(cellW, cellH).clickable { adding = true }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, "Добавить плитку") }
                    Spacer(Modifier.height(4.dp))
                    Text("Добавить", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
    if (!readOnly && items.isNotEmpty()) Text("Удерживайте плитку, чтобы перетащить или изменить", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))

    if (adding) TileEditor(b, null) { adding = false }
    editing?.let { e -> TileEditor(b, e) { editing = null } }
    deleting?.let { d ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Удалить плитку?") }, text = { Text(d.title) },
            confirmButton = { TextButton(onClick = { ql.remove(d); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
}

@Composable
private fun Tile(ql: QuickLinks, q: QuickLink, lifted: Boolean) {
    Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).then(if (lifted) Modifier.shadow(8.dp, RoundedCornerShape(14.dp)) else Modifier)) {
            TileIcon(ql, q, null)
        }
        Spacer(Modifier.height(4.dp))
        Text(q.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TileIcon(ql: QuickLinks, q: QuickLink, override: ImageBitmap?, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val img: ImageBitmap? = override ?: if (q.icon == QuickLink.ICON_LETTER) null else {
        val f = ql.iconFile(q)
        produceState<ImageBitmap?>(null, q.id, q.iconVer) {
            value = withContext(Dispatchers.IO) { try { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } catch (_: Exception) { null } }
        }.value
    }
    val shape = RoundedCornerShape(14.dp)
    if (img != null) Image(img, null, Modifier.size(size).clip(shape).background(Color.White), contentScale = ContentScale.Crop)
    else Box(Modifier.size(size).clip(shape).background(Color(if (q.color != 0) q.color else QuickLinks.COLORS[0])), contentAlignment = Alignment.Center) {
        Text(q.letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}

/** Add ([orig] == null) or edit a tile: title, URL, icon (letter + colour, site favicon or gallery picture). */
@Composable
private fun TileEditor(b: Browser, orig: QuickLink?, close: () -> Unit) {
    val ql = b.app.quickLinks
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(orig?.title ?: "") }
    var url by remember { mutableStateOf(orig?.url ?: "") }
    var color by remember { mutableStateOf(orig?.color?.takeIf { it != 0 } ?: QuickLinks.COLORS[(ql.items.size) % QuickLinks.COLORS.size]) }
    var kind by remember { mutableStateOf(orig?.icon ?: QuickLink.ICON_LETTER) }
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { s ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                        while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 30 * 1024 * 1024) return@use null }
                        QuickLinks.decodeSafe(out.toByteArray())?.let { QuickLinks.scale(it) }
                    }
                } catch (_: Exception) { null }
            }
            if (bmp != null) { pending = bmp; kind = QuickLink.ICON_IMAGE; error = null } else error = "Не удалось открыть картинку"
        }
    }

    val normUrl = QuickLinks.normalize(url)
    val preview = QuickLink(orig?.id ?: "new", title.ifBlank { QuickLinks.hostOf(normUrl ?: "") ?: "" }, normUrl ?: url, kind, color, orig?.iconVer ?: 0)
    val keepsOldIcon = orig != null && pending == null && kind != QuickLink.ICON_LETTER && orig.icon == kind
    val showImg = pending?.asImageBitmap()

    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(if (orig == null) "Новая плитка" else "Изменить плитку") },
        text = {
            Column(Modifier.verticalScrollCompat()) {
                OutlinedTextField(url, { url = it; error = null }, label = { Text("Адрес сайта") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = url.isNotBlank() && normUrl == null, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { title = it.take(40) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (kind == QuickLink.ICON_LETTER || showImg != null || keepsOldIcon) TileIcon(ql, if (keepsOldIcon) orig else preview.copy(icon = QuickLink.ICON_LETTER), showImg, 56.dp)
                    else Box(Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(when (kind) { QuickLink.ICON_FAVICON -> "Значок сайта"; QuickLink.ICON_IMAGE -> "Картинка из галереи"; else -> "Буква и цвет" },
                        style = MaterialTheme.typography.bodyMedium)
                    if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(kind == QuickLink.ICON_LETTER, { kind = QuickLink.ICON_LETTER; pending = null }, { Text("Буква") })
                    FilterChip(kind == QuickLink.ICON_FAVICON, {
                        val u = normUrl
                        if (u == null) { error = "Сначала введите адрес сайта"; return@FilterChip }
                        busy = true; error = null
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) { try { QuickLinks.fetchFavicon(u)?.let { QuickLinks.scale(it) } } catch (_: Exception) { null } }
                            busy = false
                            if (bmp != null) { pending = bmp; kind = QuickLink.ICON_FAVICON } else error = "Значок сайта не найден — оставлена буква"
                        }
                    }, { Text("Значок сайта") }, enabled = !busy)
                    FilterChip(kind == QuickLink.ICON_IMAGE, { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { Text("Из галереи") }, enabled = !busy)
                }
                if (kind == QuickLink.ICON_LETTER) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickLinks.COLORS.forEach { c ->
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).then(
                                if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).clickable { color = c })
                        }
                    }
                }
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = normUrl != null && !busy, onClick = {
                val u = normUrl ?: return@TextButton
                val t = title.trim().ifEmpty { QuickLinks.hostOf(u) ?: u }
                val base = if (orig == null) ql.add(t, u, color) else orig.copy(title = t, url = u, color = color)
                val p = pending
                if (p != null && kind != QuickLink.ICON_LETTER) {
                    ql.update(base.copy(icon = QuickLink.ICON_LETTER)) // keep fields even if the icon write fails
                    if (ql.setIcon(base, p, kind) == null) b.snack("Не удалось сохранить значок")
                } else if (kind == QuickLink.ICON_LETTER) ql.update(base.copy(icon = QuickLink.ICON_LETTER))
                else ql.update(base) // icon kind unchanged, file kept
                close()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Отмена") } })
}

@Composable
private fun Modifier.verticalScrollCompat() = this.then(Modifier.verticalScroll(rememberScrollState()))
