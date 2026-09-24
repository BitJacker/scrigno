package io.github.bitjacker.scrigno.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupDatabaseTest {

    private lateinit var db: BackupDatabase
    private val serverA = "sftp://me@nas/photos"
    private val serverB = "webdav://me@cloud/dav"

    @Before
    fun setUp() {
        db = BackupDatabase(ApplicationProvider.getApplicationContext<Context>())
        db.clear()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun backup(mediaId: Long?, path: String, onDevice: Boolean = true) = NewBackup(
        mediaId = mediaId,
        name = path.substringAfterLast('/'),
        mime = "image/jpeg",
        size = 1234,
        dateTaken = 1_700_000_000_000L + (mediaId ?: 0),
        remotePath = path,
        onDevice = onDevice,
    )

    @Test
    fun recordsWhatWasBackedUpPerServer() {
        db.insertBackup(serverA, backup(1, "Phone/2024/05/a.jpg"))
        db.insertBackup(serverA, backup(2, "Phone/2024/05/b.jpg"))
        db.insertBackup(serverB, backup(1, "Phone/2024/05/a.jpg"))

        assertEquals(setOf(1L, 2L), db.backedUpMediaIds(serverA))
        assertEquals(setOf(1L), db.backedUpMediaIds(serverB))
        assertEquals(3, db.all().size)
        assertEquals(2, db.onDeviceRecords(serverA).size)
    }

    @Test
    fun removedFromThePhoneKeepsTheServerCopy() {
        val id = db.insertBackup(serverA, backup(7, "Phone/2024/05/c.jpg"))
        db.markRemovedFromDevice(listOf(id))

        val record = db.byId(id)!!
        assertFalse(record.onDevice)
        assertNull(record.mediaId)
        assertEquals("Phone/2024/05/c.jpg", record.remotePath)
        assertTrue(db.backedUpMediaIds(serverA).isEmpty())
        assertTrue(db.onDeviceRecords(serverA).isEmpty())
    }

    @Test
    fun photosDeletedOutsideTheAppAreDetected() {
        db.insertBackup(serverA, backup(1, "Phone/a.jpg"))
        db.insertBackup(serverA, backup(2, "Phone/b.jpg"))
        db.insertBackup(serverA, backup(3, "Phone/c.jpg"))

        val missing = db.markMissingFromDevice(existingMediaIds = setOf(1L, 3L))

        assertEquals(1, missing)
        assertEquals(setOf(1L, 3L), db.backedUpMediaIds(serverA))
        assertEquals(1, db.all().count { !it.onDevice })
    }

    @Test
    fun rebuildingTheIndexDoesNotDuplicate() {
        db.insertBackup(serverA, backup(1, "Phone/2024/05/a.jpg"))

        assertFalse(db.insertIfMissing(serverA, backup(null, "Phone/2024/05/a.jpg", onDevice = false)))
        assertTrue(db.insertIfMissing(serverA, backup(null, "Phone/2024/05/new.jpg", onDevice = false)))
        assertFalse(db.insertIfMissing(serverA, backup(null, "Phone/2024/05/new.jpg", onDevice = false)))
        assertEquals(2, db.all().size)
    }

    @Test
    fun aPhotoBroughtBackIsKeptOnThePhone() {
        val id = db.insertBackup(serverA, backup(5, "Phone/x.jpg"))
        db.markRemovedFromDevice(listOf(id))
        db.linkToDevice(db.byId(id)!!, mediaId = 99)

        val record = db.byId(id)!!
        assertTrue(record.onDevice)
        assertTrue(record.keepOnDevice)
        assertEquals(99L, record.mediaId)
    }

    @Test
    fun thumbnailsAndDeletion() {
        val id = db.insertBackup(serverA, backup(5, "Phone/x.jpg"))
        db.setThumb(id, "/data/thumbs/5.jpg")
        assertEquals("/data/thumbs/5.jpg", db.byId(id)!!.thumbPath)
        db.delete(id)
        assertNull(db.byId(id))
    }
}
