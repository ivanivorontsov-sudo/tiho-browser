package org.ivanivorontsov.tiho.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Unpacks Chrome CRX2/CRX3 and Firefox XPI/ZIP, then extracts a WebExtension-subset:
 * `content_scripts` JS and CSS. Background pages, toolbars, DNR, etc. are reported as unsupported
 * (GeckoView will not run an unsigned Chrome package as a full add-on).
 * Pure JVM, unit-tested.
 */
object WebExtPackage {
    data class Script(
        val matches: List<String>,
        val excludeMatches: List<String>,
        val js: String,
        val runAt: String,
        val allFrames: Boolean,
    )
    data class Style(
        val matches: List<String>,
        val excludeMatches: List<String>,
        val css: String,
        val allFrames: Boolean,
    )
    data class Preview(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val scripts: List<Script>,
        val styles: List<Style>,
        val unsupported: List<String>,
        val geckoId: String?,
        val manifestVersion: Int,
    ) {
        val canInstall: Boolean get() = scripts.isNotEmpty() || styles.isNotEmpty()
    }

    const val MAX_PACKAGE = 32L * 1024 * 1024
    const val MAX_FILE = 4L * 1024 * 1024

    fun stripToZip(bytes: ByteArray): ByteArray {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return bytes
        require(bytes.size >= 16) { "файл слишком короткий" }
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        require(magic == "Cr24") { "это не CRX и не ZIP/XPI" }
        val ver = u32(bytes, 4)
        return when (ver) {
            2 -> {
                val pub = u32(bytes, 8)
                val sig = u32(bytes, 12)
                val off = 16 + pub + sig
                require(off in 16 until bytes.size) { "повреждённый CRX2" }
                bytes.copyOfRange(off, bytes.size)
            }
            3 -> {
                val header = u32(bytes, 8)
                val off = 12 + header
                require(off in 12 until bytes.size) { "повреждённый CRX3" }
                bytes.copyOfRange(off, bytes.size)
            }
            else -> throw IllegalArgumentException("неизвестная версия CRX: $ver")
        }
    }

    fun parse(bytes: ByteArray): Preview {
        require(bytes.size <= MAX_PACKAGE) { "пакет больше ${MAX_PACKAGE / (1024 * 1024)} МБ" }
        val files = unzip(stripToZip(bytes))
        val manifestPath = files.keys.firstOrNull { it == "manifest.json" }
            ?: files.keys.firstOrNull { it.endsWith("/manifest.json") && it.count { c -> c == '/' } == 1 }
            ?: error("в пакете нет manifest.json")
        val root = manifestPath.removeSuffix("manifest.json")
        fun file(rel: String): ByteArray? {
            val n = (root + rel).trimStart('/').replace('\\', '/')
            return files[n] ?: files[rel.trimStart('/')]
        }
        val raw = String(files.getValue(manifestPath), Charsets.UTF_8)
        val o = JSONObject(raw)
        val mv = o.optInt("manifest_version", 2)
        val locales = loadMessages(files, root, o.optString("default_locale"))
        fun i18n(s: String): String {
            val m = Regex("^__MSG_([A-Za-z0-9_]+)__$").matchEntire(s) ?: return s
            return locales[m.groupValues[1]] ?: s
        }
        val geckoId = o.optJSONObject("browser_specific_settings")?.optJSONObject("gecko")?.optString("id")
            ?.ifBlank { null }
            ?: o.optJSONObject("applications")?.optJSONObject("gecko")?.optString("id")?.ifBlank { null }
        val id = geckoId
            ?: o.optString("name").ifBlank { "extension" }.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
        val unsupported = ArrayList<String>()
        if (o.has("background") || o.has("service_worker")) unsupported += "фоновый скрипт / service worker"
        if (o.has("action") || o.has("browser_action") || o.has("page_action")) unsupported += "кнопка на панели и всплывающее окно"
        if (o.has("chrome_url_overrides")) unsupported += "замена служебных страниц (новая вкладка и т.п.)"
        if (o.has("devtools_page")) unsupported += "DevTools"
        if (o.has("sidebar_action")) unsupported += "боковая панель"
        if (o.has("commands")) unsupported += "глобальные сочетания клавиш"
        if (o.has("declarative_net_request")) unsupported += "declarativeNetRequest (сетевая блокировка расширения)"
        val scripts = ArrayList<Script>()
        val styles = ArrayList<Style>()
        val arr = o.optJSONArray("content_scripts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val cs = arr.optJSONObject(i) ?: continue
            val matches = jsonStrings(cs.optJSONArray("matches")).ifEmpty { listOf("*://*/*") }
            val excludes = jsonStrings(cs.optJSONArray("exclude_matches"))
            val runAt = when (cs.optString("run_at")) {
                "document_start" -> "document-start"
                "document_idle" -> "document-idle"
                else -> "document-end"
            }
            val allFrames = cs.optBoolean("all_frames", false)
            val jsParts = ArrayList<String>()
            for (rel in jsonStrings(cs.optJSONArray("js"))) {
                val b = file(rel) ?: throw IllegalArgumentException("в пакете нет $rel")
                require(b.size <= MAX_FILE) { "$rel больше 4 МБ" }
                jsParts += String(b, Charsets.UTF_8)
            }
            if (jsParts.isNotEmpty()) scripts += Script(matches, excludes, jsParts.joinToString("\n;\n"), runAt, allFrames)
            val cssParts = ArrayList<String>()
            for (rel in jsonStrings(cs.optJSONArray("css"))) {
                val b = file(rel) ?: throw IllegalArgumentException("в пакете нет $rel")
                require(b.size <= MAX_FILE) { "$rel больше 4 МБ" }
                cssParts += String(b, Charsets.UTF_8)
            }
            if (cssParts.isNotEmpty()) styles += Style(matches, excludes, cssParts.joinToString("\n"), allFrames)
        }
        return Preview(
            id = id,
            name = i18n(o.optString("name").ifBlank { id }),
            version = o.optString("version"),
            description = i18n(o.optString("description")),
            scripts = scripts, styles = styles, unsupported = unsupported.distinct(),
            geckoId = geckoId, manifestVersion = mv,
        )
    }

    fun toUserScripts(p: Preview, sourceUrl: String?): List<UserScript> =
        p.scripts.mapIndexed { i, s ->
            val header = buildString {
                appendLine("// ==UserScript==")
                appendLine("// @name        ${p.name}" + if (p.scripts.size > 1) " (${i + 1})" else "")
                appendLine("// @namespace   crx:${p.id}")
                appendLine("// @version     ${p.version}")
                if (p.description.isNotBlank()) appendLine("// @description ${p.description}")
                for (m in s.matches) appendLine("// @match       $m")
                for (m in s.excludeMatches) appendLine("// @exclude-match $m")
                appendLine("// @run-at      ${s.runAt}")
                if (!s.allFrames) appendLine("// @noframes")
                appendLine("// ==/UserScript==")
            }
            UserScript.parse(header + "\n" + s.js, sourceUrl)
        }

    fun toUserStyles(p: Preview, sourceUrl: String?): List<UserStyle> =
        p.styles.mapIndexed { i, s ->
            val header = buildString {
                appendLine("/* ==UserStyle==")
                appendLine("@name        ${p.name}" + if (p.styles.size > 1) " (${i + 1})" else "")
                appendLine("@namespace   crx:${p.id}")
                appendLine("@version     ${p.version}")
                if (p.description.isNotBlank()) appendLine("@description ${p.description}")
                for (m in s.matches) appendLine("@match       $m")
                for (m in s.excludeMatches) appendLine("@exclude     $m")
                appendLine("==/UserStyle== */")
            }
            UserStyle.parse(header + "\n" + s.css, sourceUrl)
        }

    private fun u32(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun unzip(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name.contains("..")) continue
                val data = z.readBytes()
                require(data.size <= MAX_FILE || name == "manifest.json") { "$name слишком большой" }
                if (data.size <= MAX_FILE) out[name] = data
            }
        }
        require(out.isNotEmpty()) { "пустой архив" }
        return out
    }

    private fun jsonStrings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun loadMessages(files: Map<String, ByteArray>, root: String, defaultLocale: String): Map<String, String> {
        val loc = defaultLocale.ifBlank { "en" }
        val candidates = listOf("${root}_locales/$loc/messages.json", "${root}_locales/en/messages.json", "${root}_locales/en_US/messages.json")
        val raw = candidates.firstNotNullOfOrNull { files[it] } ?: return emptyMap()
        return try {
            val o = JSONObject(String(raw, Charsets.UTF_8))
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).optString("message") }
        } catch (_: Exception) { emptyMap() }
    }
}
