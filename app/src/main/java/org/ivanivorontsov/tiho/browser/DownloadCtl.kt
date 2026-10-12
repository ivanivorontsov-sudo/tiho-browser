package org.ivanivorontsov.tiho.browser

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.ivanivorontsov.tiho.data.DownloadItem
import org.ivanivorontsov.tiho.security.DownloadPolicy
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Downloads handed over by Gecko (onExternalResponse): confirmation with risk warnings, then saved to Downloads. */
class DownloadCtl(private val b: Browser) {
    private val io = Executors.newFixedThreadPool(2)

    companion object {
        fun fileName(uri: String, disposition: String?, mime: String?): String {
            var name: String? = null
            if (disposition != null) {
                Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(disposition)?.let { name = try { URLDecoder.decode(it.groupValues[1].trim('"'), "UTF-8") } catch (_: Exception) { null } }
                if (name == null) Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(disposition)?.let { name = it.groupValues[1] }
            }
            if (name.isNullOrBlank()) name = try { Uri.parse(uri).lastPathSegment } catch (_: Exception) { null }
            if (name.isNullOrBlank() || uri.startsWith("blob:") || uri.startsWith("data:")) name = name?.takeIf { it.contains('.') } ?: ("download" + extFor(mime))
            return org.ivanivorontsov.tiho.security.SchemePolicy.normalize(name!!).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim('.', ' ').take(120).ifEmpty { "download" }
        }
        private fun extFor(mime: String?) = when (mime?.substringBefore(';')?.trim()) {
            "application/pdf" -> ".pdf"; "image/png" -> ".png"; "image/jpeg" -> ".jpg"; "text/plain" -> ".txt"
            "application/zip" -> ".zip"; "application/vnd.android.package-archive" -> ".apk"; else -> ""
        }
    }

    fun offer(tab: Tab, r: WebResponse) {
        val h = r.headers.mapKeys { it.key.lowercase() }
        val mime = h["content-type"]
        val name = fileName(r.uri, h["content-disposition"], mime)
        val size = h["content-length"]?.toLongOrNull()
        val risk = DownloadPolicy.assess(name, r.uri, mime)
        val text = buildString {
            append(name)
            if (size != null) append(" · ").append(android.text.format.Formatter.formatShortFileSize(b.app, size))
            append("\nИсточник: ").append(r.uri.take(100))
            val w = DownloadPolicy.warningText(risk)
            if (w.isNotEmpty()) append("\n\n").append(w)
        }
        b.show(UiDialog.Message(if (risk.dangerous) "Опасный тип файла" else "Скачать файл?", text, "Скачать", "Отмена", danger = risk.dangerous || risk.insecure) { ok ->
            if (!ok) { try { r.body?.close() } catch (_: Exception) {}; return@Message }
            save(tab, r, name, mime)
        })
    }

    private fun save(tab: Tab, r: WebResponse, name: String, mime: String?) {
        val body = r.body ?: run { b.snack("Пустой ответ сервера"); return }
        b.snack("Загрузка: $name")
        io.execute {
            try {
                r.setReadTimeoutMillis(120_000)
                val (out, uri) = open(name, mime)
                out.use { o -> body.use { it.copyTo(o, 1 shl 16) } }
                uri?.let { finish(it) }
                b.main.post {
                    if (!tab.incognito) b.app.downloads.add(DownloadItem(System.currentTimeMillis(), name, r.uri.take(500), System.currentTimeMillis(), uri?.toString()))
                    b.snack("Скачано: $name", "Открыть") { b.openDownload(name, uri?.toString(), mime) }
                }
            } catch (e: Exception) {
                Log.e("Tiho", "download", e)
                b.snack("Ошибка загрузки: ${e.message}")
            }
        }
    }


    private fun open(name: String, mime: String?): Pair<OutputStream, Uri?> {
        val ctx = b.app
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime?.substringBefore(';')?.trim() ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("MediaStore недоступен")
            return (ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("нет доступа")) to uri
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "downloads")
        dir.mkdirs()
        var f = File(dir, name); var i = 1
        while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + " ($i)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); i++ }
        return f.outputStream() to null
    }

    private fun finish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            b.app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }
}
