package io.github.bitjacker.scrigno.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a backup: scheduled (every night), after new photos, or requested with "Back up now". */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val instant = inputData.getBoolean(KEY_INSTANT, false)
        val engine = BackupEngine(c)

        // Also woken up by photos in folders that are not backed up, or by deletions: often there
        // is nothing to do, and then no notification should flash.
        if (instant && !withContext(Dispatchers.IO) { engine.hasPending() }) return Result.success()

        // Show the progress as a foreground notification when Android allows it; otherwise the
        // backup still runs in the background (and simply continues next time if it is stopped).
        runCatching { setForeground(foregroundInfo()) }

        val outcome = coroutineScope {
            val progress = launch {
                c.backupStatus.state.collect { state ->
                    if (state is BackupState.Running) {
                        Notifications.notify(
                            applicationContext,
                            Notifications.ID_PROGRESS,
                            Notifications.progress(applicationContext, state),
                        )
                    }
                }
            }
            try {
                engine.run(isStopped = { isStopped })
            } finally {
                progress.cancel()
                Notifications.cancel(applicationContext, Notifications.ID_PROGRESS)
            }
        }

        // Another backup was running: it may have looked before the newest photos existed.
        if (outcome.busy && instant) return Result.retry()
        if (outcome.skipped) return Result.success()

        val error = outcome.error
        val madeProgress = outcome.uploaded + outcome.alreadyThere > 0
        if (error == null) {
            Notifications.cancel(applicationContext, Notifications.ID_ERROR)
        } else if (outcome.permanent || manual || (!madeProgress && !instant)) {
            Notifications.backupFailed(applicationContext, error)
        } else if (madeProgress) {
            Notifications.cancel(applicationContext, Notifications.ID_ERROR)
        }
        // After new photos, a temporary problem (server switched off, away from home...) is not worth
        // a notification: the backup is retried later, and the scheduled one reports it if it lasts.
        if (manual && error == null && outcome.uploaded > 0) {
            Notifications.backupDone(applicationContext, outcome.uploaded)
        }

        if (!instant) remindToFreeSpace(c.settings.settings.value)

        return when {
            error == null -> Result.success()
            outcome.permanent -> Result.failure()
            // Server unreachable (switched off, away from home...): try again later.
            else -> Result.retry()
        }
    }

    private suspend fun remindToFreeSpace(settings: AppSettings) {
        if (!settings.freeSpaceReminder || settings.keepOnDeviceDays < 0) return
        val c = applicationContext.container
        val candidates = runCatching { c.freeSpace.candidates(settings.keepOnDeviceDays) }.getOrNull() ?: return
        if (candidates.isEmpty()) return
        Notifications.freeSpace(applicationContext, candidates.size, candidates.sumOf { it.media.size })
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification = Notifications.progress(applicationContext, null)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_PROGRESS, notification)
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_INSTANT = "instant"
    }
}
