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
) {
    companion object {
        /** APKs are only downloaded from GitHub release storage over HTTPS. */
        val ALLOWED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        fun parse(json: String): AppUpdateInfo {
            val o = try { JSONObject(json) } catch (e: Exception) { throw PackException("update.json повреждён") }
            if (o.optString("package") != "org.ivanivorontsov.tiho") throw PackException("обновление для другого приложения")
            val url = o.getString("apk_url")
            val u = try { java.net.URI(url) } catch (e: Exception) { throw PackException("плохая ссылка на APK") }
            if (u.scheme != "https" || u.host !in ALLOWED_HOSTS) throw PackException("APK разрешено загружать только по HTTPS с GitHub")
            val sha = o.getString("sha256").lowercase()
            if (!Regex("^[0-9a-f]{64}$").matches(sha)) throw PackException("плохой SHA-256")
            return AppUpdateInfo(
                o.getInt("version_code"), o.getString("version_name"), o.optInt("min_version_code", 0), url, sha,
                o.getLong("size"), o.getLong("expires"), o.optString("cert_sha256").uppercase(), o.optString("notes"),
            )
        }
    }
}

class AppUpdateVerifier(private val keys: List<Minisign.PublicKey>, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    sealed class Check {
        data class Available(val info: AppUpdateInfo) : Check()
        data class UpToDate(val info: AppUpdateInfo) : Check()
        data class TooOld(val info: AppUpdateInfo) : Check()
    }

    fun verify(bytes: ByteArray, sig: String, installedVersionCode: Int, expectedCertSha256: String?): Check {
        val v = try { Minisign.verify(bytes, sig, keys) } catch (e: SecurityException) { throw PackException("подпись update.json не прошла проверку: ${e.message}") }
        val info = AppUpdateInfo.parse(String(bytes, Charsets.UTF_8))
        if (!v.trustedComment.contains("tiho-app ${info.versionCode} ")) throw PackException("подписанный комментарий не совпадает с версией")
        if (info.expires <= now()) throw PackException("срок действия update.json истёк (возможна атака повтором)")
        if (expectedCertSha256 != null && info.certSha256.isNotEmpty() && info.certSha256 != expectedCertSha256.uppercase())
            throw PackException("APK подписан другим ключом")
        if (info.size <= 0 || info.size > 64L * 1024 * 1024) throw PackException("недопустимый размер APK")
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
}
