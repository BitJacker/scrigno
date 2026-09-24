package io.github.bitjacker.scrigno.data.remote

import android.content.Context
import io.github.bitjacker.scrigno.core.backup.MediaFiles
import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.data.db.BackupDatabase
import io.github.bitjacker.scrigno.data.db.BackupRecord
import io.github.bitjacker.scrigno.data.db.NewBackup
import io.github.bitjacker.scrigno.data.library.LibraryRepository
import io.github.bitjacker.scrigno.data.media.MediaStoreSource
import io.github.bitjacker.scrigno.data.media.ThumbnailStore
import io.github.bitjacker.scrigno.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Photos that live only on the server: downloaded on demand into a size-limited cache when you look
 * at them, brought back to the phone, or deleted for good.
 */
class OriginalsRepository(
    context: Context,
    private val sessions: RemoteSessions,
    private val database: BackupDatabase,
    private val thumbnails: ThumbnailStore,
    private val mediaStore: MediaStoreSource,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) {
    private val dir = File(context.cacheDir, "originals")

    fun cachedFile(record: BackupRecord): File {
        val extension = RemotePaths.splitExtension(record.name).second
        return File(dir, "${record.id}$extension")
    }

    fun isCached(record: BackupRecord): Boolean = cachedFile(record).let { it.exists() && it.length() == record.size }

    /** Returns the original file, downloading it if needed. [onProgress] goes from 0 to 1. */
    suspend fun fetch(record: BackupRecord, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val target = cachedFile(record)
        if (target.exists() && (record.size <= 0 || target.length() == record.size)) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        checkServer(record)
        dir.mkdirs()
        val temp = File(dir, "${record.id}.part")
        try {
            sessions.withStorage { storage ->
                temp.outputStream().buffered().use { out ->
                    storage.download(record.remotePath, out) { bytes ->
                        if (record.size > 0) onProgress((bytes.toFloat() / record.size).coerceIn(0f, 1f))
                    }
                }
            }
            if (!temp.renameTo(target)) throw IOException("Cannot save ${record.name} in the cache")
        } finally {
            temp.delete()
        }
        if (record.thumbPath == null) {
            thumbnails.saveFromFile(record.id, target, record.isVideo)?.let { path ->
                database.setThumb(record.id, path)
                library.refresh()
            }
        }
        trim(keep = target)
        target
    }

    /** Copies a photo from the server back into the phone's gallery. */
    suspend fun restoreToDevice(record: BackupRecord) = withContext(Dispatchers.IO) {
        val file = fetch(record)
        val mediaId = mediaStore.insert(file, record.name, record.mime ?: MediaFiles.mimeType(record.name) ?: "image/jpeg", record.dateTaken)
        database.linkToDevice(record, mediaId)
        library.refresh()
    }

    /** Deletes the file from the server, for good. */
    suspend fun deleteFromServer(record: BackupRecord) = withContext(Dispatchers.IO) {
        checkServer(record)
        sessions.withStorage { storage -> storage.delete(record.remotePath) }
        database.delete(record.id)
        thumbnails.delete(record.id)
        cachedFile(record).delete()
        library.refresh()
    }

    /**
     * Rebuilds the index from the server: every photo found in this phone's folder that the app does
     * not know about yet is added to the gallery (useful after reinstalling the app).
     */
    suspend fun rebuildIndex(onFound: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val server = settings.server.value ?: throw RemoteException("No server configured")
        val identity = server.identity()
        val root = settings.deviceFolder
        var added = 0
        sessions.withStorage { storage ->
            fun walk(path: String, depth: Int) {
                for (file in storage.list(path)) {
                    if (file.isDirectory) {
                        if (depth < 4) walk(file.path, depth + 1)
                    } else if (MediaFiles.isMedia(file.name)) {
                        val record = NewBackup(
                            mediaId = null,
                            name = file.name,
                            mime = MediaFiles.mimeType(file.name),
                            size = file.size,
                            dateTaken = dateOf(file.path, file.modifiedMillis),
                            remotePath = file.path,
                            onDevice = false,
                        )
                        if (database.insertIfMissing(identity, record)) {
                            added++
                            onFound(added)
                        }
                    }
                }
            }
            if (storage.stat(root)?.isDirectory == true) walk(root, 0)
        }
        library.refresh()
        added
    }

    fun cacheSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        dir.deleteRecursively()
    }

    /** Keeps the cache under the limit chosen in the settings, dropping the least recently viewed. */
    private fun trim(keep: File) {
        val limit = settings.settings.value.cacheLimitMb * 1024L * 1024L
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            if (file == keep) continue
            total -= file.length()
            file.delete()
        }
    }

    private fun checkServer(record: BackupRecord) {
        val current = settings.server.value ?: throw RemoteException("No server configured")
        if (current.identity() != record.server) throw OtherServerException(record.server)
    }

    /**
     * The date of a photo found on the server: its modification time when it is plausible (Scrigno
     * sets it to the capture date where the protocol allows), otherwise the month of its folder.
     */
    private fun dateOf(path: String, modified: Long?): Long {
        val match = MONTH_FOLDER.find(path)
        val folderMonth = match?.let {
            runCatching {
                LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), 1)
            }.getOrNull()
        }
        if (modified != null && modified > 0) {
            val date = java.time.Instant.ofEpochMilli(modified).atZone(ZoneId.systemDefault()).toLocalDate()
            if (folderMonth == null || (date.year == folderMonth.year && date.month == folderMonth.month)) return modified
        }
        return folderMonth?.atStartOfDay(ZoneId.systemDefault())?.plusHours(12)?.toInstant()?.toEpochMilli()
            ?: modified ?: 0L
    }

    private companion object {
        val MONTH_FOLDER = Regex("(\\d{4})/(\\d{2})/[^/]+$")
    }
}

/** The photo was backed up to a server that is not the one configured now. */
class OtherServerException(val server: String) : IOException("This photo is on another server: $server")
