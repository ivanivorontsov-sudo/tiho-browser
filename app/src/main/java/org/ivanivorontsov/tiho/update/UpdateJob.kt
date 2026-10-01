package org.ivanivorontsov.tiho.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.ivanivorontsov.tiho.TihoApp

/**
 * Daily background check for signed content packs (and the signed update.json) via JobScheduler:
 * no extra dependencies, respects the «только по Wi-Fi» option (unmetered network) and battery.
 */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as TihoApp
        if (!app.prefs.contentAuto) {
            if (!app.prefs.appUpdateCheck) return false
            app.appUpdater.check(manual = false) { jobFinished(params, false) }
            return true
        }
        app.contentPacks.check(manual = false) {
            if (app.prefs.appUpdateCheck) app.appUpdater.check(manual = false) { jobFinished(params, false) }
            else jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 4201

        fun schedule(ctx: Context, enabled: Boolean, wifiOnly: Boolean) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) { js.cancel(ID); return }
            val job = JobInfo.Builder(ID, ComponentName(ctx, UpdateJob::class.java))
                .setPeriodic(24L * 60 * 60 * 1000, 6L * 60 * 60 * 1000)
                .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(false)
                .build()
            val existing = js.getPendingJob(ID)
            if (existing != null && existing.networkType == job.networkType) return
            js.schedule(job)
        }
    }
}
