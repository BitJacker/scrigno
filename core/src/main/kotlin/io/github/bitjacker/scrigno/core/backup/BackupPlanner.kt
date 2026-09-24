package io.github.bitjacker.scrigno.core.backup

import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The facts about a photo or video on the phone that matter for planning a backup. */
data class MediaRef(
    val id: Long,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val bucketId: String?,
    val isVideo: Boolean,
)

/** Pure decisions, kept apart from Android so they can be unit tested. */
object BackupPlanner {

    /**
     * Media that still has to be uploaded, newest first (the latest photos are the most precious).
     *
     * @param selectedBuckets folders chosen by the user, or null for "every folder".
     */
    fun pending(
        media: List<MediaRef>,
        alreadyBackedUp: Set<Long>,
        selectedBuckets: Set<String>?,
        includeVideos: Boolean,
    ): List<MediaRef> = media
        .filter { it.id !in alreadyBackedUp }
        .filter { includeVideos || !it.isVideo }
        .filter { selectedBuckets == null || it.bucketId in selectedBuckets }
        .sortedByDescending { it.dateTakenMillis }

    /**
     * Whether a backed up item is old enough to leave the phone, keeping the last [keepDays] days.
     * A negative [keepDays] means "never remove anything".
     */
    fun isOldEnoughToFree(dateTakenMillis: Long, nowMillis: Long, keepDays: Int): Boolean {
        if (keepDays < 0) return false
        return dateTakenMillis < nowMillis - TimeUnit.DAYS.toMillis(keepDays.toLong())
    }

    /** Time to wait before the next run at [hour]:00, used to schedule the nightly backup. */
    fun delayUntilHour(now: ZonedDateTime, hour: Int): Duration {
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
