package io.github.bitjacker.scrigno.data.library

import android.content.Context
import io.github.bitjacker.scrigno.data.db.BackupDatabase
import io.github.bitjacker.scrigno.data.db.BackupRecord
import io.github.bitjacker.scrigno.data.media.DeviceMedia
import io.github.bitjacker.scrigno.data.media.MediaAccess
import io.github.bitjacker.scrigno.data.media.MediaPermissions
import io.github.bitjacker.scrigno.data.media.MediaStoreSource
import io.github.bitjacker.scrigno.data.media.MediaThumb
import io.github.bitjacker.scrigno.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where a photo is right now. */
enum class Location {
    /** Only on the phone: not backed up (yet). */
    DEVICE,

    /** On the phone and safe on the server. */
    BOTH,

    /** Only on the server: removed from the phone to free up space (or deleted there). */
    SERVER,
}

/** One photo or video of the timeline, wherever it is. */
data class LibraryItem(val device: DeviceMedia?, val record: BackupRecord?) {
    init {
        require(device != null || record != null)
    }

    val key: String = if (device != null) "d${device.id}" else "r${record!!.id}"
    val name: String get() = device?.name ?: record!!.name
    val dateTaken: Long get() = device?.dateTaken ?: record!!.dateTaken
    val isVideo: Boolean get() = device?.isVideo ?: record!!.isVideo
    val size: Long get() = device?.size ?: record!!.size
    val durationMs: Long get() = device?.durationMs ?: record!!.durationMs
    val mime: String get() = device?.mime ?: record?.mime ?: "image/jpeg"
    val width: Int get() = device?.width ?: record!!.width
    val height: Int get() = device?.height ?: record!!.height

    val location: Location
        get() = when {
            device != null && record != null -> Location.BOTH
            device != null -> Location.DEVICE
            else -> Location.SERVER
        }

    /** What Coil should load for the grid. */
    val thumbnailModel: Any?
        get() = when {
            device != null -> MediaThumb(device.uri, device.id, device.isVideo)
            record?.thumbPath != null -> File(record.thumbPath)
            else -> null
        }
}

data class Library(
    val items: List<LibraryItem> = emptyList(),
    val loaded: Boolean = false,
    val access: MediaAccess = MediaAccess.NONE,
) {
    val deviceCount: Int get() = items.count { it.device != null }
    val backedUpCount: Int get() = items.count { it.location == Location.BOTH }
    val serverOnlyCount: Int get() = items.count { it.location == Location.SERVER }
}

/** Merges the phone's gallery with the index of what is on the server. */
class LibraryRepository(
    private val context: Context,
    private val mediaStore: MediaStoreSource,
    private val database: BackupDatabase,
    private val settings: SettingsRepository,
) {
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val mutex = Mutex()

    suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val access = MediaPermissions.access(context)
            val device = if (access != MediaAccess.NONE) mediaStore.query(includeVideos = true) else emptyList()
            val server = settings.server.value?.identity()
            val records = database.all()

            // For each photo on the phone, the record of the current server (if backed up there).
            val recordsByMedia = records
                .filter { it.onDevice && it.mediaId != null }
                .groupBy { it.mediaId!! }
            val deviceIds = HashSet<Long>(device.size * 2)
            val items = ArrayList<LibraryItem>(device.size + records.size / 4)
            for (media in device) {
                deviceIds += media.id
                val record = recordsByMedia[media.id]?.firstOrNull { it.server == server }
                items += LibraryItem(media, record)
            }
            // Everything else we know about lives only on the server.
            for (record in records) {
                val stillOnPhone = record.onDevice && record.mediaId != null && record.mediaId in deviceIds
                if (!stillOnPhone) items += LibraryItem(null, record)
            }
            items.sortByDescending { it.dateTaken }
            _library.value = Library(items, loaded = true, access = access)
        }
    }

    fun find(key: String): LibraryItem? = _library.value.items.firstOrNull { it.key == key }
}
