package io.github.bitjacker.scrigno.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class BackupPlannerTest {

    private fun ref(id: Long, date: Long, bucket: String = "camera", video: Boolean = false) =
        MediaRef(id = id, sizeBytes = 10, dateTakenMillis = date, bucketId = bucket, isVideo = video)

    @Test
    fun pendingSkipsBackedUpAndSortsNewestFirst() {
        val media = listOf(ref(1, 100), ref(2, 300), ref(3, 200))
        val pending = BackupPlanner.pending(media, alreadyBackedUp = setOf(2L), selectedBuckets = null, includeVideos = true)
        assertEquals(listOf(3L, 1L), pending.map { it.id })
    }

    @Test
    fun pendingHonoursFoldersAndVideos() {
        val media = listOf(ref(1, 1, "camera"), ref(2, 2, "whatsapp"), ref(3, 3, "camera", video = true))
        assertEquals(
            listOf(1L),
            BackupPlanner.pending(media, emptySet(), selectedBuckets = setOf("camera"), includeVideos = false).map { it.id },
        )
        assertEquals(
            listOf(3L, 1L),
            BackupPlanner.pending(media, emptySet(), selectedBuckets = setOf("camera"), includeVideos = true).map { it.id },
        )
    }

    @Test
    fun freeSpacePolicy() {
        val now = TimeUnit.DAYS.toMillis(100)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        assertTrue(BackupPlanner.isOldEnoughToFree(tenDaysAgo, now, keepDays = 7))
        assertFalse(BackupPlanner.isOldEnoughToFree(tenDaysAgo, now, keepDays = 30))
        assertTrue(BackupPlanner.isOldEnoughToFree(now - 1, now, keepDays = 0))
        assertFalse(BackupPlanner.isOldEnoughToFree(0, now, keepDays = -1))
    }

    @Test
    fun delayUntilHour() {
        val zone = ZoneId.of("Europe/Rome")
        val evening = ZonedDateTime.of(2024, 3, 10, 22, 15, 0, 0, zone)
        assertEquals(Duration.ofHours(3).plusMinutes(45), BackupPlanner.delayUntilHour(evening, 2))
        val night = ZonedDateTime.of(2024, 3, 10, 1, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(1), BackupPlanner.delayUntilHour(night, 2))
        val exactly = ZonedDateTime.of(2024, 3, 10, 2, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(24), BackupPlanner.delayUntilHour(exactly, 2))
    }
}
