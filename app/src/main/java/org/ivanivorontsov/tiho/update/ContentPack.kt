package org.ivanivorontsov.tiho.update

import org.json.JSONObject
import java.io.File

/**
 * Signed content packs (filter lists, shim JS, payment-domains.json, min_webview_major, userscript catalog).
 *
 * Flow: manifest.json + manifest.json.minisig → Ed25519 (one of 2 pinned keys, not the APK key) → monotonic
 * version, not expired, min_app_version → each file downloaded to a staging dir and checked against its SHA-256/size
 * → atomic swap (current → previous, staging → current) → caller validates (adblock engine builds) → rollback on
 * failure. Pure JVM (unit-tested); networking and Android glue live in [ContentPacks].
 */
class PackException(msg: String) : Exception(msg)

data class PackFile(val path: String, val url: String, val sha256: String, val size: Long, val kind: String) {
    val isCode get() = kind == "code"
}

data class PackManifest(
    val version: Long,
    val created: String,
    val expires: Long,
    val minAppVersion: Int,
    val minWebViewMajor: Int,
    val files: List<PackFile>,
    val raw: String,
) {
    companion object {
        private val PATH = Regex("^[a-z0-9_-][a-z0-9._-]*(/[a-z0-9_-][a-z0-9._-]*)*$")

        fun parse(json: String): PackManifest {
            val o = try { JSONObject(json) } catch (e: Exception) { throw PackException("манифест повреждён") }
            if (o.optInt("format", 0) != 1) throw PackException("неизвестный формат манифеста")
            if (o.optString("name") != "tiho-content") throw PackException("это не пакет Тихо")
            val arr = o.optJSONArray("files") ?: throw PackException("в манифесте нет файлов")
            val files = (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                val path = f.getString("path")
                val url = f.optString("url", path)
                if (!PATH.matches(path) || path.contains("..")) throw PackException("недопустимый путь: $path")
                if (url.contains("..") || url.contains("://") || url.startsWith("/") || url.contains('\\')) throw PackException("недопустимый URL: $url")
                val sha = f.getString("sha256").lowercase()
                if (!Regex("^[0-9a-f]{64}$").matches(sha)) throw PackException("плохой SHA-256 у $path")
                PackFile(path, url, sha, f.getLong("size"), f.optString("kind", "data"))
            }
            if (files.map { it.path }.toSet().size != files.size) throw PackException("повторяющиеся файлы")
            return PackManifest(
                version = o.getLong("version"),
                created = o.optString("created"),
                expires = o.getLong("expires"),
                minAppVersion = o.optInt("min_app_version", 0),
                minWebViewMajor = o.optInt("min_webview_major", 0),
                files = files, raw = json,
            )
        }
    }
}

class ContentPackVerifier(private val keys: List<Minisign.PublicKey>, private val appVersionCode: Int, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    /** @param highWater highest pack version ever accepted on this device (anti-replay / anti-downgrade). */
    fun verifyManifest(bytes: ByteArray, sig: String, highWater: Long): PackManifest {
        val v = try { Minisign.verify(bytes, sig, keys) } catch (e: SecurityException) { throw PackException("подпись не прошла проверку: ${e.message}") }
        val m = PackManifest.parse(String(bytes, Charsets.UTF_8))
        // The trusted comment is signed too: it must name the same version (defence against a swapped manifest).
        if (!v.trustedComment.contains("tiho-content v${m.version} ")) throw PackException("подписанный комментарий не совпадает с версией")
        if (m.version <= highWater) throw PackException(if (m.version == highWater) "уже установлена" else "откат на старую версию ${m.version} (есть $highWater) отклонён")
        if (m.expires <= now()) throw PackException("срок действия пакета истёк")
        if (m.minAppVersion > appVersionCode) throw PackException("нужна более новая версия приложения")
        return m
    }

    fun verifyFile(f: PackFile, data: ByteArray) {
        if (data.size.toLong() != f.size) throw PackException("размер ${f.path} не совпадает")
        if (Minisign.sha256Hex(data) != f.sha256) throw PackException("SHA-256 ${f.path} не совпадает")
    }
}

/** On-disk layout: root/current, root/previous, root/staging. Swaps are directory renames (atomic on one filesystem). */
class ContentPackStore(val root: File) {
    val current get() = File(root, "current")
    val previous get() = File(root, "previous")
    private val staging get() = File(root, "staging")

    fun installedManifest(): PackManifest? = try { PackManifest.parse(File(current, "manifest.json").readText()) } catch (_: Exception) { null }

    fun file(path: String): File? = File(current, path).takeIf { it.isFile && it.canonicalPath.startsWith(current.canonicalPath + File.separator) }

    /** Downloads every file of [m] to staging and verifies it. Code files are skipped when [allowCode] is false. */
    fun stage(m: PackManifest, sig: String, verifier: ContentPackVerifier, allowCode: Boolean, fetch: (PackFile) -> ByteArray): File {
        root.mkdirs()
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            for (f in m.files) {
                if (f.isCode && !allowCode) continue
                val data = fetch(f)
                verifier.verifyFile(f, data)
                val out = File(staging, f.path)
                if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw PackException("недопустимый путь")
                out.parentFile?.mkdirs()
                out.writeBytes(data)
            }
            File(staging, "manifest.json").writeText(m.raw)
            File(staging, "manifest.json.minisig").writeText(sig)
            File(staging, "code-allowed").writeText(allowCode.toString())
        } catch (e: Exception) {
            staging.deleteRecursively(); throw e
        }
        return staging
    }

    fun commit() {
        if (!staging.isDirectory) throw PackException("нечего устанавливать")
        previous.deleteRecursively()
        if (current.exists() && !current.renameTo(previous)) throw PackException("не удалось сохранить предыдущую версию")
        if (!staging.renameTo(current)) {
            previous.renameTo(current)
            throw PackException("не удалось установить пакет")
        }
    }

    /** Restores the previous pack (or the built-in defaults when there was none). */
    fun rollback() {
        val broken = File(root, "broken")
        broken.deleteRecursively()
        if (current.exists()) current.renameTo(broken)
        if (previous.exists()) previous.renameTo(current)
        broken.deleteRecursively()
    }
}

/** Orchestrates one update: verify → stage → swap → validate → rollback on failure. */
class ContentPackUpdater(
    private val store: ContentPackStore,
    private val verifier: ContentPackVerifier,
) {
    sealed class Result {
        data class Installed(val version: Long) : Result()
        data class UpToDate(val version: Long) : Result()
        data class RolledBack(val version: Long, val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * @param fetchText fetches "manifest.json" / "manifest.json.minisig"; [fetchFile] fetches pack files.
     * @param validate checks the swapped-in pack (e.g. adblock engine builds); false → rollback.
     */
    fun update(
        highWater: Long,
        allowCode: Boolean,
        fetchText: (String) -> ByteArray,
        fetchFile: (PackFile) -> ByteArray,
        validate: () -> Boolean,
        onAccepted: (Long) -> Unit,
    ): Result {
        val mBytes: ByteArray
        val sig: String
        try {
            mBytes = fetchText("manifest.json")
            sig = String(fetchText("manifest.json.minisig"), Charsets.UTF_8)
        } catch (e: Exception) { return Result.Failed("нет связи с сервером обновлений: ${e.message}") }
        val m = try { verifier.verifyManifest(mBytes, sig, highWater) } catch (e: PackException) {
            return if (e.message == "уже установлена") Result.UpToDate(highWater) else Result.Failed(e.message ?: "ошибка")
        }
        try { store.stage(m, sig, verifier, allowCode, fetchFile) } catch (e: Exception) { return Result.Failed(e.message ?: "ошибка загрузки") }
        try { store.commit() } catch (e: Exception) { return Result.Failed(e.message ?: "ошибка установки") }
        onAccepted(m.version) // high-water mark moves even if validation fails: a broken pack is never retried
        val ok = try { validate() } catch (_: Throwable) { false }
        if (!ok) {
            store.rollback()
            try { validate() } catch (_: Throwable) {}
            return Result.RolledBack(m.version, "пакет v${m.version} сломал блокировщик — возвращена предыдущая версия")
        }
        return Result.Installed(m.version)
    }
}
