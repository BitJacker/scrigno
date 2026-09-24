package io.github.bitjacker.scrigno.backup

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The automatic backup is really scheduled: every night, and when new photos appear. */
@RunWith(RobolectricTestRunner::class)
class BackupSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerConfig(protocol = Protocol.SFTP, host = "nas.local", username = "mario", password = "secret")
    private val configured = AppSettings(onboardingDone = true)
    private lateinit var scheduler: BackupScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        scheduler = BackupScheduler(context)
    }

    private fun work(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun waiting(name: String): WorkInfo = work(name).single { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun theDefaultsBackUpEveryNightAndWatchForNewPhotos() {
        scheduler.apply(configured, server, replace = true)

        val nightly = waiting(BackupScheduler.PERIODIC)
        assertEquals(24 * 3600 * 1000L, nightly.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, nightly.constraints.requiredNetworkType)

        val watch = waiting(BackupScheduler.NEW_MEDIA).constraints
        val uris = watch.contentUriTriggers.filter { it.isTriggeredForDescendants }.map { it.uri }
        assertTrue(MediaStore.Images.Media.EXTERNAL_CONTENT_URI in uris)
        assertTrue(MediaStore.Video.Media.EXTERNAL_CONTENT_URI in uris)
        assertTrue("a burst of photos is one backup", watch.contentTriggerUpdateDelayMillis > 0)
    }

    @Test
    fun nothingIsScheduledBeforeTheSetupOrWhenTurnedOff() {
        scheduler.apply(AppSettings(), server, replace = true)
        assertTrue(work(BackupScheduler.PERIODIC).isEmpty())
        assertTrue(work(BackupScheduler.NEW_MEDIA).isEmpty())

        scheduler.apply(configured, server, replace = true)
        scheduler.apply(configured.copy(instantBackup = false), server, replace = true)
        waiting(BackupScheduler.PERIODIC)
        assertTrue(work(BackupScheduler.NEW_MEDIA).all { it.state == WorkInfo.State.CANCELLED })

        scheduler.apply(configured.copy(autoBackup = false), server, replace = true)
        assertTrue(work(BackupScheduler.PERIODIC).all { it.state == WorkInfo.State.CANCELLED })

        scheduler.apply(configured, null, replace = true)
        assertTrue(work(BackupScheduler.PERIODIC).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun newPhotosAreBackedUpOnTheChosenNetwork() {
        scheduler.backupNewMedia(configured)
        assertEquals(NetworkType.UNMETERED, waiting(BackupScheduler.NEW_MEDIA_BACKUP).constraints.requiredNetworkType)

        // Not running yet: replaced by one with the new settings, not queued twice.
        scheduler.backupNewMedia(configured.copy(wifiOnly = false, chargingOnly = true))
        val backup = waiting(BackupScheduler.NEW_MEDIA_BACKUP).constraints
        assertEquals(NetworkType.CONNECTED, backup.requiredNetworkType)
        assertTrue(backup.requiresCharging())
    }

    @Test
    fun theFirstBackupStartsRightAfterTheSetupNotAtNight() {
        scheduler.startFirstBackup(configured.copy(autoBackup = false), server)
        scheduler.startFirstBackup(configured, null)
        assertTrue(work(BackupScheduler.NEW_MEDIA_BACKUP).isEmpty())

        scheduler.startFirstBackup(configured, server)
        val first = waiting(BackupScheduler.NEW_MEDIA_BACKUP)
        assertEquals(0L, first.initialDelayMillis)
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
    }

    @Test
    fun aNewPhotoQueuesABackupAndTheWatchStartsAgain() {
        context.container.settings.saveServer(server)
        context.container.settings.update { it.copy(onboardingDone = true) }
        val worker = TestListenableWorkerBuilder<NewMediaWorker>(context)
            .setTriggeredContentUris(listOf(Uri.parse("content://media/external/images/media/42")))
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        waiting(BackupScheduler.NEW_MEDIA_BACKUP)
        waiting(BackupScheduler.NEW_MEDIA)
    }

    @Test
    fun theWatchStopsWhenInstantBackupIsTurnedOff() {
        context.container.settings.saveServer(server)
        context.container.settings.update { it.copy(onboardingDone = true, instantBackup = false) }
        val worker = TestListenableWorkerBuilder<NewMediaWorker>(context)
            .setTriggeredContentUris(listOf(Uri.parse("content://media/external/images/media/42")))
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        assertTrue(work(BackupScheduler.NEW_MEDIA_BACKUP).isEmpty())
        assertTrue(work(BackupScheduler.NEW_MEDIA).isEmpty())
    }

    @Test
    fun cancelAllStopsEverything() {
        scheduler.apply(configured, server, replace = true)
        scheduler.backupNewMedia(configured)
        scheduler.cancelAll().result.get()
        for (name in listOf(BackupScheduler.PERIODIC, BackupScheduler.NEW_MEDIA, BackupScheduler.NEW_MEDIA_BACKUP)) {
            assertTrue(name, work(name).all { it.state == WorkInfo.State.CANCELLED })
        }
    }
}
