package io.github.bitjacker.scrigno.core.remote

import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.backup.Uploader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The same behaviour is expected from every protocol: each implementation runs these tests. */
abstract class RemoteStorageContract {

    /** A storage pointing to a running test server, with valid credentials. */
    protected abstract fun storage(): RemoteStorage

    /** Same server, wrong password. */
    protected abstract fun storageWithWrongPassword(): RemoteStorage

    private val photo = ByteArray(300_000) { (it % 251).toByte() }
    private val dir = "Pixel 8/2024/05"
    private val taken = 1_715_680_800_000L

    @Test
    fun uploadsVerifiesAndDeduplicates() {
        storage().use { storage ->
            storage.connect()
            assertTrue(storage.isConnected)
            storage.mkdirs(dir)
            assertTrue(storage.stat(dir)!!.isDirectory)
            assertTrue(storage.stat("Pixel 8")!!.isDirectory)

            var progress = 0L
            val first = Uploader.upload(storage, dir, "IMG 1:é.jpg", photo.size.toLong(), taken, { ByteArrayInputStream(photo) }) {
                progress = it
            }
            assertFalse(first.alreadyPresent)
            assertEquals("$dir/IMG 1_é.jpg", first.remotePath)
            assertEquals(photo.size.toLong(), first.bytes)
            assertEquals(photo.size.toLong(), progress)

            val stat = storage.stat(first.remotePath)
            assertNotNull(stat)
            assertEquals(photo.size.toLong(), stat!!.size)
            assertFalse(stat.isDirectory)
            assertEquals("IMG 1_é.jpg", stat.name)

            // The temporary file is gone, only the final one is listed.
            assertEquals(listOf("IMG 1_é.jpg"), storage.list(dir).map { it.name })
            assertEquals(listOf("2024"), storage.list("Pixel 8").filter { it.isDirectory }.map { it.name })

            val downloaded = ByteArrayOutputStream()
            storage.download(first.remotePath, downloaded)
            assertArrayEquals(photo, downloaded.toByteArray())

            // Same name and size: recognised as already backed up (e.g. after reinstalling the app).
            val again = Uploader.upload(storage, dir, "IMG 1:é.jpg", photo.size.toLong(), taken, { ByteArrayInputStream(photo) })
            assertTrue(again.alreadyPresent)
            assertEquals(first.remotePath, again.remotePath)

            // Same name, different photo: never overwritten, a new name is used.
            val other = ByteArray(1234) { 7 }
            val second = Uploader.upload(storage, dir, "IMG 1:é.jpg", other.size.toLong(), null, { ByteArrayInputStream(other) })
            assertFalse(second.alreadyPresent)
            assertEquals("$dir/IMG 1_é_1.jpg", second.remotePath)
            assertEquals(photo.size.toLong(), storage.stat(first.remotePath)!!.size)
        }
    }

    @Test
    fun renamesAndDeletes() {
        storage().use { storage ->
            storage.connect()
            storage.mkdirs("a/b")
            storage.upload("a/b/one.jpg", ByteArrayInputStream(photo), photo.size.toLong())
            storage.rename("a/b/one.jpg", "a/b/two.jpg")
            assertNull(storage.stat("a/b/one.jpg"))
            assertEquals(photo.size.toLong(), storage.stat("a/b/two.jpg")!!.size)
            storage.delete("a/b/two.jpg")
            assertNull(storage.stat("a/b/two.jpg"))
            assertNull(storage.stat("missing/nothing.jpg"))
            assertTrue(storage.list("a/b").isEmpty())
        }
    }

    @Test
    fun mkdirsIsIdempotent() {
        storage().use { storage ->
            storage.connect()
            storage.mkdirs("x/y/z")
            storage.mkdirs("x/y/z")
            storage.mkdirs("x")
            assertTrue(storage.stat("x/y/z")!!.isDirectory)
            assertEquals(listOf("z"), storage.list(RemotePaths.join("x", "y")).map { it.name })
        }
    }

    @Test
    fun reconnects() {
        storage().use { storage ->
            storage.connect()
            storage.mkdirs("r")
            storage.connect()
            assertTrue(storage.stat("r")!!.isDirectory)
        }
    }

    @Test
    fun wrongPasswordIsReportedAsSuch() {
        storageWithWrongPassword().use { storage ->
            assertThrows(AuthenticationException::class.java) { storage.connect() }
        }
    }
}
