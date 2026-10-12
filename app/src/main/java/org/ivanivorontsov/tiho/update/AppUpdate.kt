package org.ivanivorontsov.tiho.update

import org.json.JSONObject

/**
 * Signed in-app update descriptor (update.json + update.json.minisig in the public tiho-updates repo).
 * Pure JVM (unit-tested).
 */
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val expires: Long,
    val certSha256: String,
    val notes: String,
    /** Per-ABI APKs (format 2). The top-level apk_url stays arm64 for Тихо ≤ 1.1.0. */
    val apks: Map<String, Apk> = emptyMap(),
    val geckoVersion: String = "",
) {
    data class Apk(val url: String, val sha256: String, val size: Long)

    /** Picks the APK for the device ABIs (in preference order); falls back to the top-level one. */
    fun forAbis(abis: List<String>): AppUpdateInfo {
        val abi = abis.firstOrNull { it in apks } ?: return this
        val a = apks.getValue(abi)
        return copy(apkUrl = a.url, sha256 = a.sha256, size = a.size)
    }

    companion object {
        /** APKs are only downloaded from GitHub release storage over HTTPS. */
        val ALLOWED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        private fun checkUrl(url: String) {
            val u = try { java.net.URI(url) } catch (e: Exception) { throw PackException("плохая ссылка на APK") }
            if (u.scheme != "https" || u.host !in ALLOWED_HOSTS) throw PackException("APK разрешено загружать только по HTTPS с GitHub")
        }
        private fun checkSha(sha: String): String {
            val s = sha.lowercase()
            if (!Regex("^[0-9a-f]{64}$").matches(s)) throw PackException("плохой SHA-256")
            return s
        }

        fun parse(json: String): AppUpdateInfo {
            val o = try { JSONObject(json) } catch (e: Exception) { throw PackException("update.json повреждён") }
            if (o.optString("package") != "org.ivanivorontsov.tiho") throw PackException("обновление для другого приложения")
            val url = o.getString("apk_url")
            checkUrl(url)
            val sha = checkSha(o.getString("sha256"))
            val apks = LinkedHashMap<String, Apk>()
            o.optJSONObject("apks")?.let { m ->
                for (abi in m.keys()) {
                    val a = m.getJSONObject(abi)
                    checkUrl(a.getString("url"))
                    apks[abi] = Apk(a.getString("url"), checkSha(a.getString("sha256")), a.getLong("size"))
                }
            }
            return AppUpdateInfo(
                o.getInt("version_code"), o.getString("version_name"), o.optInt("min_version_code", 0), url, sha,
                o.getLong("size"), o.getLong("expires"), o.optString("cert_sha256").uppercase(), o.optString("notes"),
                apks, o.optString("gecko_version"),
            )
        }
    }
}

class AppUpdateVerifier(private val keys: List<Minisign.PublicKey>, private val now: () -> Long = { System.currentTimeMillis() / 1000 },
                        private val expectedFile: String = "update-v2.json") {
    sealed class Check {
        data class Available(val info: AppUpdateInfo) : Check()
        data class UpToDate(val info: AppUpdateInfo) : Check()
        data class TooOld(val info: AppUpdateInfo) : Check()
    }

    fun verify(bytes: ByteArray, sig: String, installedVersionCode: Int, expectedCertSha256: String?): Check {
        val v = try { Minisign.verify(bytes, sig, keys) } catch (e: SecurityException) { throw PackException("подпись update.json не прошла проверку: ${e.message}") }
        val info = AppUpdateInfo.parse(String(bytes, Charsets.UTF_8))
        val tc = Minisign.parseComment(v.trustedComment)
        if (tc == null || tc.kind != "app" || tc.version != info.versionCode.toLong() || (tc.file != null && tc.file != expectedFile))
            throw PackException("подписанный комментарий не совпадает с версией")
        if (info.expires <= now()) throw PackException("срок действия update.json истёк (возможна атака повтором)")
        if (expectedCertSha256 != null && info.certSha256.isNotEmpty() && info.certSha256 != expectedCertSha256.uppercase())
            throw PackException("APK подписан другим ключом")
        if (info.size <= 0 || info.size > MAX_APK || info.apks.values.any { it.size <= 0 || it.size > MAX_APK })
            throw PackException("недопустимый размер APK")
        return when {
            info.versionCode <= installedVersionCode -> Check.UpToDate(info)
            installedVersionCode < info.minVersionCode -> Check.TooOld(info)
            else -> Check.Available(info)
        }
    }

    fun verifyApk(info: AppUpdateInfo, data: ByteArray) {
        if (data.size.toLong() != info.size) throw PackException("размер APK не совпадает")
        if (Minisign.sha256Hex(data) != info.sha256) throw PackException("SHA-256 APK не совпадает")
    }

    /** Streaming variant for large (GeckoView) APKs. */
    fun verifyApk(info: AppUpdateInfo, file: java.io.File) {
        if (file.length() != info.size) throw PackException("размер APK не совпадает")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins -> val buf = ByteArray(1 shl 16); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        if (md.digest().joinToString("") { "%02x".format(it) } != info.sha256) throw PackException("SHA-256 APK не совпадает")
    }

    companion object { const val MAX_APK = 200L * 1024 * 1024 }
}
