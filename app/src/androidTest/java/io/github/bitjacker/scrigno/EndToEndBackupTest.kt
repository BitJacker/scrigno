package io.github.bitjacker.scrigno

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.bitjacker.scrigno.backup.BackupEngine
import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.remote.RemoteStorageFactory
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.library.Location
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.UUID

/**
 * The whole life of a photo, on a real phone (emulator) against a real server, for every protocol:
 * taken → backed up → verified on the server → removed from the phone to free up space → still in
 * the gallery → opened (downloaded again, identical) → brought back to the phone → deleted.
 */
@RunWith(Parameterized::class)
class EndToEndBackupTest(private val case: Case) {

    /** One server; the short name keeps test reports readable (and never shows the password). */
    class Case(val server: ServerConfig?) {
        override fun toString(): String = server?.let { "${it.protocol.name}-port${it.port}" } ?: "no-server"
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            TestServers.all.map { arrayOf<Any>(Case(it)) }.ifEmpty { listOf(arrayOf<Any>(Case(null))) }

        private const val FOLDER = "ScrignoE2E"
    }

    private val server: ServerConfig? get() = case.server

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.all)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val c get() = context.container

    @Before
    fun setUp() {
        assumeTrue("No test servers: pass the instrumentation argument scrignoServers", server != null)
        runBlocking { resetApp(context) }
        TestImages.deleteFolder(context, FOLDER)
    }

    @After
    fun tearDown() {
        TestImages.deleteFolder(context, FOLDER)
    }

    @Test
    fun backupFreeUpSpaceOpenRestoreAndDelete() = runBlocking {
        val config = server!!
        val deviceFolder = "e2e-${config.protocol.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        c.settings.saveServer(config)
        c.settings.update {
            it.copy(onboardingDone = true, autoBackup = false, includeVideos = false, deviceFolder = deviceFolder)
        }

        // Three photos, taken in two different months.
        val images = listOf(
            TestImages.create(context, FOLDER, "IMG_20230510_120000.jpg", "2023:05:10 12:00:00", Color.RED),
            TestImages.create(context, FOLDER, "IMG_20230511_090000.jpg", "2023:05:11 09:00:00", Color.GREEN),
            TestImages.create(context, FOLDER, "IMG_20240102_180000.jpg", "2024:01:02 18:00:00", Color.BLUE),
        )
        val ours = c.mediaStore.query().filter { it.bucketName == FOLDER }
        assertEquals(3, ours.size)
        // Only this test's folder, whatever else is on the emulator.
        c.settings.update { it.copy(allFolders = false, selectedFolders = setOf(ours.first().bucketId)) }

        // 1. Backup.
        val outcome = BackupEngine(c).run { false }
        assertNull("backup error: ${outcome.error}", outcome.error)
        assertEquals(0, outcome.failed)
        assertEquals(3, outcome.uploaded + outcome.alreadyThere)

        // The files are on the server, complete, in <phone>/<year>/<month>/.
        RemoteStorageFactory.create(c.settings.server.value!!).use { storage ->
            storage.connect()
            for (media in ours) {
                val path = RemotePaths.join(deviceFolder, RemotePaths.monthFolder(media.dateTaken), media.name)
                val remote = storage.stat(path)
                assertNotNull("not on the server: $path", remote)
                assertEquals(media.size, remote!!.size)
            }
        }

        // 2. A second run has nothing left to do.
        val again = BackupEngine(c).run { false }
        assertEquals(0, again.uploaded + again.alreadyThere)
        assertEquals(0, again.failed)

        c.library.refresh()
        val inGallery = c.library.library.value.items.filter { it.device?.bucketName == FOLDER }
        assertEquals(3, inGallery.size)
        assertTrue(inGallery.all { it.location == Location.BOTH })

        // 3. Free up space, keeping nothing on the phone.
        val candidates = c.freeSpace.candidates(keepDays = 0).filter { it.media.bucketName == FOLDER }
        assertEquals(3, candidates.size)
        val verified = c.freeSpace.verify(candidates) { _, _ -> }
        assertEquals(3, verified.size)
        c.freeSpace.deleteDirectly(verified) // the test created these files: no confirmation needed
        assertEquals(3, c.freeSpace.finish(verified).count)

        val onServer = c.library.library.value.items.filter {
            it.location == Location.SERVER && it.record?.remotePath?.startsWith(deviceFolder) == true
        }
        assertEquals(3, onServer.size)
        assertTrue(
            "a small preview is kept for the gallery",
            onServer.all { item -> item.record!!.thumbPath?.let { File(it).length() > 0 } == true },
        )

        // 4. Opening a photo downloads it again: identical to the original.
        val first = onServer.first { it.name == images[0].name }.record!!
        assertArrayEquals(images[0].bytes, c.originals.fetch(first).readBytes())

        // 5. Bring one back to the phone.
        val second = onServer.first { it.name == images[1].name }.record!!
        c.originals.restoreToDevice(second)
        val restored = c.database.byId(second.id)!!
        assertTrue(restored.onDevice)
        assertTrue(restored.keepOnDevice)
        assertNotNull(c.mediaStore.query().firstOrNull { it.id == restored.mediaId })

        // 6. Delete one from the server, for good.
        val third = onServer.first { it.name == images[2].name }.record!!
        c.originals.deleteFromServer(third)
        assertNull(c.database.byId(third.id))
        RemoteStorageFactory.create(c.settings.server.value!!).use { storage ->
            storage.connect()
            assertNull(storage.stat(third.remotePath))
        }
    }
}
