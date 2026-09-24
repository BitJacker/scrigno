package io.github.bitjacker.scrigno.data.settings

/**
 * User preferences. Stored only on this phone, never sent anywhere.
 *
 * @property instantBackup besides the scheduled backup, back up new photos and videos about a
 * minute after they are taken (part of the automatic backup: off when [autoBackup] is off).
 * @property keepOnDeviceDays photos older than this many days, already safe on the server, may be
 * removed from the phone to free up space. -1 means never; 0 means as soon as they are backed up.
 */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val autoBackup: Boolean = true,
    val instantBackup: Boolean = true,
    val intervalHours: Int = 24,
    val preferredHour: Int = 2,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    val allFolders: Boolean = true,
    val selectedFolders: Set<String> = emptySet(),
    val keepOnDeviceDays: Int = NEVER_REMOVE,
    val freeSpaceReminder: Boolean = true,
    val cacheLimitMb: Int = 1024,
    val deviceFolder: String = "",
) {
    companion object {
        const val NEVER_REMOVE = -1
        val INTERVAL_OPTIONS = listOf(6, 12, 24, 72, 168)
        val KEEP_OPTIONS = listOf(NEVER_REMOVE, 0, 7, 30, 90, 365)
        val CACHE_OPTIONS = listOf(256, 512, 1024, 2048, 5120)
    }
}

/** Summary of the last backup run, shown in the Backup tab. */
data class LastBackup(
    val finishedAt: Long,
    val uploaded: Int,
    val failed: Int,
    val error: String?,
)
