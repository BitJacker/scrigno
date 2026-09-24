package io.github.bitjacker.scrigno.backup

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.bitjacker.scrigno.container

/**
 * Started by Android shortly after photos or videos are added or changed (see
 * [BackupScheduler.apply]): queues a backup of the new files, then waits for the next change.
 */
class NewMediaWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val c = applicationContext.container
        val settings = c.settings.settings.value
        val active = settings.autoBackup && settings.instantBackup && settings.onboardingDone &&
            c.settings.server.value != null
        if (!active) return Result.success() // turned off meanwhile: stop watching
        if (triggeredContentUris.isNotEmpty() || triggeredContentAuthorities.isNotEmpty()) {
            c.scheduler.backupNewMedia(settings)
        }
        c.scheduler.watchNewMediaAgain()
        return Result.success()
    }
}
