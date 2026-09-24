package io.github.bitjacker.scrigno.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RemotePathsTest {

    @Test
    fun normalize() {
        assertEquals("", RemotePaths.normalize(""))
        assertEquals("", RemotePaths.normalize("  "))
        assertEquals("/", RemotePaths.normalize("/"))
        assertEquals("a/b", RemotePaths.normalize("a//b/"))
        assertEquals("/srv/photos", RemotePaths.normalize(" /srv/./photos/ "))
        assertEquals("/srv/photos", RemotePaths.normalize("\\srv\\photos"))
    }

    @Test
    fun join() {
        assertEquals("a/b", RemotePaths.join("", "a/b"))
        assertEquals("/base/a/b", RemotePaths.join("/base", "a", "b"))
        assertEquals("/a", RemotePaths.join("/", "a"))
        assertEquals("/", RemotePaths.join("/", ""))
        assertEquals("base/a", RemotePaths.join("base/", "/a"))
        assertEquals("", RemotePaths.join("", ""))
    }

    @Test
    fun parentAndName() {
        assertEquals("a", RemotePaths.parent("a/b"))
        assertEquals("", RemotePaths.parent("a"))
        assertEquals("/", RemotePaths.parent("/a"))
        assertEquals("/a/b", RemotePaths.parent("/a/b/c.jpg"))
        assertEquals("c.jpg", RemotePaths.name("/a/b/c.jpg"))
        assertEquals("", RemotePaths.name(""))
    }

    @Test
    fun monthFolder() {
        val millis = LocalDateTime.of(2024, 5, 31, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2024/05", RemotePaths.monthFolder(millis, ZoneOffset.UTC))
        // Same instant, but in Rome it is already June.
        assertEquals("2024/06", RemotePaths.monthFolder(millis, ZoneId.of("Europe/Rome")))
    }

    @Test
    fun sanitizeFileName() {
        assertEquals("IMG_0001.jpg", RemotePaths.sanitizeFileName("IMG_0001.jpg"))
        assertEquals("a_b_c_d.jpg", RemotePaths.sanitizeFileName("a/b\\c:d.jpg"))
        assertEquals("file", RemotePaths.sanitizeFileName("..."))
        assertEquals("photo", RemotePaths.sanitizeFileName("photo. "))
        assertEquals("Foto è bella.heic", RemotePaths.sanitizeFileName("Foto è bella.heic"))
        val long = "x".repeat(400) + ".jpeg"
        val clean = RemotePaths.sanitizeFileName(long)
        assertTrue(clean.length <= 180)
        assertTrue(clean.endsWith(".jpeg"))
    }

    @Test
    fun collisionSuffixAndTempNames() {
        assertEquals("IMG_1.jpg", RemotePaths.withCollisionSuffix("IMG_1.jpg", 0))
        assertEquals("IMG_1_2.jpg", RemotePaths.withCollisionSuffix("IMG_1.jpg", 2))
        assertEquals("README_1", RemotePaths.withCollisionSuffix("README", 1))
        assertEquals(".hidden_1", RemotePaths.withCollisionSuffix(".hidden", 1))
        assertTrue(RemotePaths.isTempName(RemotePaths.tempName("a.jpg")))
        assertFalse(RemotePaths.isTempName("a.jpg"))
    }

    @Test
    fun mediaFiles() {
        assertTrue(MediaFiles.isMedia("IMG_1.JPG"))
        assertTrue(MediaFiles.isMedia("clip.mov"))
        assertTrue(MediaFiles.isVideo("clip.MP4"))
        assertFalse(MediaFiles.isMedia("notes.txt"))
        assertFalse(MediaFiles.isMedia(RemotePaths.tempName("IMG_1.jpg")))
        assertEquals("image/heic", MediaFiles.mimeType("a.heic"))
    }
}
