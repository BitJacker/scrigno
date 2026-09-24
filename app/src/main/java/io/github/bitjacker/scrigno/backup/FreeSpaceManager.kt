package io.github.bitjacker.scrigno.backup

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import io.github.bitjacker.scrigno.core.backup.BackupPlanner
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.data.db.BackupDatabase
import io.github.bitjacker.scrigno.data.db.BackupRecord
import io.github.bitjacker.scrigno.data.library.LibraryRepository
import io.github.bitjacker.scrigno.data.media.DeviceMedia
import io.github.bitjacker.scrigno.data.media.MediaStoreSource
import io.github.bitjacker.scrigno.data.media.ThumbnailStore
import io.github.bitjacker.scrigno.data.remote.RemoteSessions
import io.github.bitjacker.scrigno.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo that is safe on the server and could leave the phone. */
data class FreeCandidate(val record: BackupRecord, val media: DeviceMedia)

/**
 * "Free up space": removes from the phone the photos already safe on the server, like the
 * optimized storage of commercial clouds, but with your server.
 *
 * Nothing is deleted without a final check: each file must be on the server with the right size,
 * and on Android 11+ the system itself asks the user to confirm.
 */
class FreeSpaceManager(
    private val context: Context,
    private val database: BackupDatabase,
    private val mediaStore: MediaStoreSource,
    private val thumbnails: ThumbnailStore,
    private val sessions: RemoteSessions,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
) {

    /** Backed up photos older than [keepDays] days (0 = all of them) that are still on the phone. */
    suspend fun candidates(keepDays: Int): List<FreeCandidate> = withContext(Dispatchers.IO) {
        if (keepDays < 0) return@withContext emptyList()
        val server = settings.server.value ?: return@withContext emptyList()
        val device = mediaStore.query(includeVideos = true).associateBy { it.id }
        val now = System.currentTimeMillis()
        database.onDeviceRecords(server.identity()).mapNotNull { record ->
            val media = record.mediaId?.let { device[it] } ?: return@mapNotNull null
            if (record.keepOnDevice) return@mapNotNull null
            if (!BackupPlanner.isOldEnoughToFree(media.dateTaken, now, keepDays)) return@mapNotNull null
            FreeCandidate(record, media)
        }
    }

    /** Candidates for a single photo of the gallery (the "remove from phone" button). */
    suspend fun candidateFor(record: BackupRecord): FreeCandidate? = withContext(Dispatchers.IO) {
        val mediaId = record.mediaId ?: return@withContext null
        val media = mediaStore.query(includeVideos = true).firstOrNull { it.id == mediaId } ?: return@withContext null
        FreeCandidate(record, media)
    }

    /**
     * Checks on the server that every file is really there, complete, and saves a small preview of
     * each one for the gallery. Returns only the verified items.
     */
    suspend fun verify(items: List<FreeCandidate>, onProgress: (Int, Int) -> Unit): List<FreeCandidate> =
        withContext(Dispatchers.IO) {
            val server = settings.server.value ?: throw RemoteException("No server configured")
            val identity = server.identity()
            val verified = sessions.withStorage { storage ->
                items.filterIndexed { index, item ->
                    onProgress(index + 1, items.size)
                    val remote = storage.stat(item.record.remotePath)
                    item.record.server == identity && remote != null && !remote.isDirectory && remote.size == item.record.size
                }
            }
            verified.forEach { item ->
                thumbnails.saveFromDevice(item.record.id, item.media)?.let { database.setThumb(item.record.id, it) }
            }
            verified
        }

    /** Android 11+: the system dialog that asks the user to confirm the deletion. */
    fun deleteRequest(items: List<FreeCandidate>): PendingIntent? {
        if (Build.VERSION.SDK_INT < 30 || items.isEmpty()) return null
        return MediaStore.createDeleteRequest(context.contentResolver, items.map { it.media.uri })
    }

    /** Android 8-10: delete directly (the permission was granted at the beginning). */
    suspend fun deleteDirectly(items: List<FreeCandidate>) = withContext(Dispatchers.IO) {
        mediaStore.deleteDirectly(items.map { it.media.uri })
    }

    /** After the deletion: the photos that are really gone are now "on the server only". */
    suspend fun finish(items: List<FreeCandidate>): FreeResult = withContext(Dispatchers.IO) {
        val stillThere = mediaStore.existing(items.map { it.media.id })
        val removed = items.filter { it.media.id !in stillThere }
        database.markRemovedFromDevice(removed.map { it.record.id })
        library.refresh()
        FreeResult(removed.size, removed.sumOf { it.media.size })
    }

    companion object {
        /** How many photos a single system dialog may delete (keeps the request small). */
        const val MAX_PER_REQUEST = 500
    }
}

data class FreeResult(val count: Int, val bytes: Long)
