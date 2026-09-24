package io.github.bitjacker.scrigno

import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.bitjacker.scrigno.backup.BackupScheduler
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteStorageFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * What matters most: a photo just taken reaches the server by itself. Nothing in this test starts
 * the backup, and no screen of the app is open: Android notices the new photo and runs the backup,
 * as it does on a real phone.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticBackupTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.all)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val c get() = context.container

    @After
    fun tearDown() {
        runBlocking { resetApp(context) }
        TestImages.deleteFolder(context, FOLDER)
    }

    @Test
    fun aNewPhotoGoesToTheServerByItself() = runBlocking {
        val server = TestServers.all.firstOrNull { it.protocol == Protocol.WEBDAV }
        assumeTrue("needs the WebDAV test server", server != null)
        resetApp(context)
        TestImages.deleteFolder(context, FOLDER)
        // Android may postpone the background work of apps not used lately; on a phone Scrigno
        // is used, here it was just installed.
        shell("am set-standby-bucket ${context.packageName} active")

        // Set up as a user would: server, automatic backup (only this test's folder).
        val earlier = TestImages.create(context, FOLDER, "IMG_20240601_090000.jpg", "2024:06:01 09:00:00", Color.BLUE)
        val bucket = c.mediaStore.query().first { it.id == earlier.id }.bucketId
        c.settings.saveServer(server!!)
        c.settings.update {
            it.copy(
                onboardingDone = true,
                autoBackup = true,
                instantBackup = true,
                wifiOnly = false, // the emulator's network may look like mobile data
                allFolders = false,
                selectedFolders = setOf(bucket),
                includeVideos = false,
                deviceFolder = "auto-" + UUID.randomUUID().toString().take(8),
            )
        }
        c.scheduler.apply(c.settings.settings.value, c.settings.server.value, replace = true)
        waitUntil(30_000, "the app does not watch for new photos") {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupScheduler.NEW_MEDIA).get()
                .any { it.state == WorkInfo.State.ENQUEUED }
        }
        delay(3_000) // WorkManager hands the watch over to Android's JobScheduler

        // Take a photo... and wait.
        val photo = TestImages.create(context, FOLDER, "IMG_20240601_100000.jpg", "2024:06:01 10:00:00", Color.GREEN)
        val identity = c.settings.server.value!!.identity()
        waitUntil(5 * 60_000, "the new photo was not backed up by itself within 5 minutes") {
            photo.id in c.database.backedUpMediaIds(identity)
        }
        // The one taken before the setup goes along with it.
        waitUntil(60_000, "the earlier photo was not backed up") { earlier.id in c.database.backedUpMediaIds(identity) }

        // Both are on the server, complete.
        val records = c.database.all().filter { it.server == identity }
        RemoteStorageFactory.create(c.settings.server.value!!).use { storage ->
            storage.connect()
            for (image in listOf(photo, earlier)) {
                val record = records.single { it.mediaId == image.id }
                assertEquals(image.bytes.size.toLong(), storage.stat(record.remotePath)?.size)
                storage.delete(record.remotePath)
            }
        }
    }

    /** Runs a command as the adb shell would, and waits for it to end. */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private suspend fun waitUntil(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            delay(1_000)
        }
    }

    private companion object {
        const val FOLDER = "ScrignoAuto"
    }
}
