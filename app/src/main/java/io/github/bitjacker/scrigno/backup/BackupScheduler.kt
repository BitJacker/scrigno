package io.github.bitjacker.scrigno.backup

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
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

/**
 * Schedules the automatic backup with Android's WorkManager (survives reboots, respects Doze):
 * a periodic one, at night by default, and one shortly after new photos are taken.
 */
class BackupScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Enables, updates or cancels the automatic backup to match the settings.
     * @param replace restart the schedule (the user changed it) instead of keeping the current one.
     */
    fun apply(settings: AppSettings, server: ServerConfig?, replace: Boolean) {
        if (!settings.autoBackup || server == null || !settings.onboardingDone) {
            workManager.cancelUniqueWork(PERIODIC)
            stopWatchingNewMedia()
            return
        }
        val hours = settings.intervalHours.coerceAtLeast(1).toLong()
        val builder = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints(settings))
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
        if (settings.instantBackup) watchNewMedia(ExistingWorkPolicy.KEEP) else stopWatchingNewMedia()
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

    /**
     * New photos or videos appeared (called by [NewMediaWorker], off the main thread): back them up
     * as soon as the network and the battery allow it.
     */
    fun backupNewMedia(settings: AppSettings) {
        val states = workManager.getWorkInfosForUniqueWork(NEW_MEDIA_BACKUP).get().map { it.state }
        val policy = when {
            // Nothing running: start (again) with the current settings, without waiting for a retry.
            WorkInfo.State.RUNNING !in states -> ExistingWorkPolicy.REPLACE
            // The running backup may have looked before these files existed: run once more after it.
            WorkInfo.State.BLOCKED !in states -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> return // that second run is already queued
        }
        workManager.enqueueUniqueWork(NEW_MEDIA_BACKUP, policy, automaticBackup(settings))
    }

    /**
     * Right after the setup: backs up what is already on the phone without waiting for the night,
     * as soon as the network (Wi‑Fi by default) and the battery allow it.
     */
    fun startFirstBackup(settings: AppSettings, server: ServerConfig?) {
        if (!settings.autoBackup || server == null) return
        workManager.enqueueUniqueWork(NEW_MEDIA_BACKUP, ExistingWorkPolicy.KEEP, automaticBackup(settings))
    }

    /** Waits for the next new photo; called by [NewMediaWorker], so it starts once that one ends. */
    fun watchNewMediaAgain() = watchNewMedia(ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Cancels every automatic and manual backup. */
    fun cancelAll(): Operation = workManager.cancelAllWorkByTag(TAG)

    private fun watchNewMedia(policy: ExistingWorkPolicy) {
        // Android itself watches the media store and starts the worker (even when Scrigno is not
        // running) once the changes stop for a little while: a burst of photos is one backup.
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(20, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            .build()
        val request = OneTimeWorkRequestBuilder<NewMediaWorker>()
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(NEW_MEDIA, policy, request)
    }

    private fun stopWatchingNewMedia() {
        workManager.cancelUniqueWork(NEW_MEDIA)
        workManager.cancelUniqueWork(NEW_MEDIA_BACKUP)
    }

    /** A backup the user did not ask for right now: it follows the network and battery settings. */
    private fun automaticBackup(settings: AppSettings) = OneTimeWorkRequestBuilder<BackupWorker>()
        .setConstraints(constraints(settings))
        .setInputData(workDataOf(BackupWorker.KEY_INSTANT to true))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(TAG)
        .build()

    private fun constraints(settings: AppSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    internal companion object {
        const val PERIODIC = "scrigno-periodic-backup"
        const val NOW = "scrigno-backup-now"
        const val NEW_MEDIA = "scrigno-new-media"
        const val NEW_MEDIA_BACKUP = "scrigno-new-media-backup"
        const val TAG = "scrigno-backup"
    }
}
