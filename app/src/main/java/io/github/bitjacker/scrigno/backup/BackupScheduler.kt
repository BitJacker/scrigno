package io.github.bitjacker.scrigno.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.bitjacker.scrigno.core.backup.BackupPlanner
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Schedules the automatic backup with Android's WorkManager (survives reboots, respects Doze). */
class BackupScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Enables, updates or cancels the periodic backup to match the settings.
     * @param replace restart the schedule (the user changed it) instead of keeping the current one.
     */
    fun apply(settings: AppSettings, server: ServerConfig?, replace: Boolean) {
        if (!settings.autoBackup || server == null || !settings.onboardingDone) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val hours = settings.intervalHours.coerceAtLeast(1).toLong()
        val builder = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints(settings.wifiOnly, settings.chargingOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.MINUTES)
            .addTag(TAG)
        if (hours >= 24) {
            // Daily (or less frequent) backups start at the preferred hour, at night by default.
            val delay = BackupPlanner.delayUntilHour(ZonedDateTime.now(), settings.preferredHour)
            builder.setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
        }
        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
            builder.build(),
        )
    }

    /** "Back up now": runs as soon as a network is available (any network, the user asked for it). */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(BackupWorker.KEY_MANUAL to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** True while a manual backup waits for a network connection. */
    fun manualBackupWaiting(): Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(NOW)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }

    fun cancelAll() {
        workManager.cancelUniqueWork(NOW)
        workManager.cancelUniqueWork(PERIODIC)
    }

    private fun constraints(wifiOnly: Boolean, chargingOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    private companion object {
        const val PERIODIC = "scrigno-periodic-backup"
        const val NOW = "scrigno-backup-now"
        const val TAG = "scrigno-backup"
    }
}
