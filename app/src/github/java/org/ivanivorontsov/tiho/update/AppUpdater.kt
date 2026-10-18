package org.ivanivorontsov.tiho.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.ivanivorontsov.tiho.BuildConfig
import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.security.Net
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app APK update: signed update.json (HTTPS) → Ed25519 → APK over HTTPS → size + SHA-256 → signing certificate
 * must equal the installed one → PackageInstaller session; Android itself asks the user to confirm.
 */
class AppUpdater(private val app: TihoApp) {
    private val exec = Executors.newSingleThreadExecutor()
    private val verifier = AppUpdateVerifier(TrustedKeys.appKeys)
    val available = mutableStateOf<AppUpdateInfo?>(null)
    val progress = mutableStateOf<String?>(null)
    @Volatile var lastInfo: AppUpdateInfo? = null
        private set

    fun check(manual: Boolean, done: ((String) -> Unit)? = null) = exec.execute {
        val status = try {
            val bytes = Net.get(TrustedKeys.UPDATE_URL, 256 * 1024)
            val sig = Net.getText(TrustedKeys.UPDATE_URL + ".minisig", 4096)
            val r = verifier.verify(bytes, sig, BuildConfig.VERSION_CODE, TrustedKeys.APK_CERT_SHA256)
            app.prefs.appLastOk = System.currentTimeMillis().toString()
            val abis = Build.SUPPORTED_ABIS.toList()
            val c = when (r) {
                is AppUpdateVerifier.Check.Available -> AppUpdateVerifier.Check.Available(r.info.forAbis(abis))
                is AppUpdateVerifier.Check.UpToDate -> AppUpdateVerifier.Check.UpToDate(r.info.forAbis(abis))
                is AppUpdateVerifier.Check.TooOld -> AppUpdateVerifier.Check.TooOld(r.info.forAbis(abis))
            }
            when (c) {
                is AppUpdateVerifier.Check.Available -> { lastInfo = c.info; available.value = c.info; "Доступна версия ${c.info.versionName}" + (if (c.info.geckoVersion.isNotEmpty()) ", GeckoView ${c.info.geckoVersion}" else "") + " (подпись проверена)" }
                is AppUpdateVerifier.Check.UpToDate -> { lastInfo = c.info; available.value = null; "Установлена последняя версия ${BuildConfig.VERSION_NAME} (update.json подписан, проверен)" }
                is AppUpdateVerifier.Check.TooOld -> { lastInfo = c.info; "Версия ${c.info.versionName} требует сначала обновиться вручную" }
            }
        } catch (e: Exception) { "Ошибка проверки: ${e.message}" }
        app.prefs.appUpdateStatus = status
        app.prefs.appLastCheck = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())
        Log.i("Tiho", "app update check (manual=$manual): $status")
        done?.invoke(status)
    }

    /** No verified update check for 14 days (counted from install/upgrade if never checked). */
    fun stale(): Boolean {
        val last = app.prefs.appLastOk.toLongOrNull() ?: 0L
        val since = if (last > 0) last else try { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime } catch (_: Exception) { System.currentTimeMillis() }
        return System.currentTimeMillis() - since > 14L * 24 * 3600 * 1000
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads, verifies and hands the APK to PackageInstaller. */
    fun install(info: AppUpdateInfo, done: (String) -> Unit) = exec.execute {
        val dir = File(app.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "tiho-${info.versionCode}.apk")
        try {
            progress.value = "Загрузка…"
            val sha = Net.download(info.apkUrl, apk, info.size + 1) { n -> progress.value = "Загрузка: ${n * 100 / info.size}%" }
            if (apk.length() != info.size) throw SecurityException("размер APK не совпадает")
            if (sha != info.sha256) throw SecurityException("SHA-256 APK не совпадает")
            checkApkIdentity(apk, info)
            progress.value = "Установка…"
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("tiho.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(app, InstallReceiver::class.java).setAction(ACTION)
                s.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
            progress.value = null
            done("Подтвердите установку в системном окне")
        } catch (e: Exception) {
            apk.delete(); progress.value = null
            done("Обновление отклонено: ${e.message}")
        }
    }

    /** The downloaded APK must be our package, newer, and signed with the same certificate as the installed app. */
    private fun checkApkIdentity(apk: File, info: AppUpdateInfo) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw SecurityException("файл не является APK")
        if (archive.packageName != app.packageName) throw SecurityException("APK другого приложения")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (code != info.versionCode.toLong() || code <= BuildConfig.VERSION_CODE) throw SecurityException("неверная версия APK")
        val sigs = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") archive.signatures
        val certs = sigs?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(":") { b -> "%02X".format(b) } }.orEmpty()
        if (TrustedKeys.APK_CERT_SHA256 !in certs) throw SecurityException("APK подписан чужим ключом")
    }

    companion object { const val ACTION = "org.ivanivorontsov.tiho.INSTALL_STATUS" }
}

/** PackageInstaller callback: shows the system confirmation dialog when user action is required. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (e: Exception) { Log.e("Tiho", "installer confirm", e) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Tiho", "update installed")
            else -> Log.w("Tiho", "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
