package io.github.bitjacker.scrigno.ui.gallery

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.backup.FreeCandidate
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.data.db.BackupRecord
import io.github.bitjacker.scrigno.data.library.Library
import io.github.bitjacker.scrigno.data.library.LibraryItem
import io.github.bitjacker.scrigno.data.library.Location
import io.github.bitjacker.scrigno.data.media.DeviceMedia
import io.github.bitjacker.scrigno.ui.common.SystemDeleteRequest
import io.github.bitjacker.scrigno.util.ErrorMessages
import io.github.bitjacker.scrigno.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.YearMonth

enum class GalleryFilter(@StringRes val label: Int) {
    ALL(R.string.filter_all),
    ON_DEVICE(R.string.filter_on_device),
    SERVER_ONLY(R.string.filter_server_only),
    NOT_BACKED_UP(R.string.filter_not_backed_up);

    fun matches(item: LibraryItem): Boolean = when (this) {
        ALL -> true
        ON_DEVICE -> item.device != null
        SERVER_ONLY -> item.location == Location.SERVER
        NOT_BACKED_UP -> item.location == Location.DEVICE
    }
}

/** Rows of the grid: a month title or a photo. */
sealed interface GridEntry {
    val key: String

    data class Header(val month: YearMonth) : GridEntry {
        override val key: String = "h$month"
    }

    data class Photo(val item: LibraryItem) : GridEntry {
        override val key: String = item.key
    }
}

data class GalleryState(
    val library: Library = Library(),
    val filter: GalleryFilter = GalleryFilter.ALL,
    val items: List<LibraryItem> = emptyList(),
    val entries: List<GridEntry> = emptyList(),
)

/** Filters the timeline and inserts a title before the first photo of each month. */
internal fun buildGalleryState(library: Library, filter: GalleryFilter): GalleryState {
    val items = library.items.filter(filter::matches)
    val entries = ArrayList<GridEntry>(items.size + 64)
    var month: YearMonth? = null
    for (item in items) {
        val itemMonth = Formatters.yearMonth(item.dateTaken)
        if (itemMonth != month) {
            entries += GridEntry.Header(itemMonth)
            month = itemMonth
        }
        entries += GridEntry.Photo(item)
    }
    return GalleryState(library, filter, items, entries)
}

sealed interface DownloadState {
    data class Loading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val c = application.container
    private val filter = MutableStateFlow(GalleryFilter.ALL)

    val state: StateFlow<GalleryState> = combine(c.library.library, filter) { library, selected -> buildGalleryState(library, selected) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, GalleryState())

    private val _downloads = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<Long, DownloadState>> = _downloads.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _deleteRequest = MutableStateFlow<SystemDeleteRequest?>(null)
    val deleteRequest: StateFlow<SystemDeleteRequest?> = _deleteRequest.asStateFlow()

    /** What to do once the system confirms the deletion from the phone. */
    private var afterDeletion: AfterDeletion? = null

    private data class AfterDeletion(val freed: List<FreeCandidate>, val alsoFromServer: BackupRecord?)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { c.library.refresh() }
    }

    fun setFilter(value: GalleryFilter) {
        filter.value = value
    }

    private fun say(@StringRes message: Int, vararg args: Any) {
        _messages.tryEmit(getApplication<Application>().getString(message, *args))
    }

    private fun sayError(error: Throwable) {
        _messages.tryEmit(ErrorMessages.describe(getApplication(), error))
    }

    /** Downloads the original of a photo that lives only on the server (when it is displayed). */
    fun ensureOriginal(item: LibraryItem) {
        val record = item.record ?: return
        if (item.location != Location.SERVER) return
        val current = _downloads.value[record.id]
        if (current is DownloadState.Loading || (current is DownloadState.Ready && current.file.exists())) return
        viewModelScope.launch {
            _downloads.update { it + (record.id to DownloadState.Loading(0f)) }
            try {
                val file = c.originals.fetch(record) { progress ->
                    _downloads.update { it + (record.id to DownloadState.Loading(progress)) }
                }
                _downloads.update { it + (record.id to DownloadState.Ready(file)) }
            } catch (e: Exception) {
                _downloads.update { it + (record.id to DownloadState.Failed(ErrorMessages.describe(getApplication(), e))) }
            }
        }
    }

    fun retryDownload(item: LibraryItem) {
        val record = item.record ?: return
        _downloads.update { it - record.id }
        ensureOriginal(item)
    }

    /** A content:// address other apps can open (sharing, video player). Downloads if needed. */
    suspend fun contentUri(item: LibraryItem): Uri? {
        item.device?.let { return it.uri }
        val record = item.record ?: return null
        return try {
            _busy.value = true
            val file = c.originals.fetch(record) { progress ->
                _downloads.update { it + (record.id to DownloadState.Loading(progress)) }
            }
            _downloads.update { it + (record.id to DownloadState.Ready(file)) }
            val app = getApplication<Application>()
            FileProvider.getUriForFile(app, app.packageName + ".files", file)
        } catch (e: Exception) {
            sayError(e)
            null
        } finally {
            _busy.value = false
        }
    }

    /** Brings a photo from the server back into the phone's gallery. */
    fun restore(item: LibraryItem) {
        val record = item.record ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                c.originals.restoreToDevice(record)
                say(R.string.viewer_restored)
            } catch (e: Exception) {
                sayError(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Deletes a photo that is only on the server. */
    fun deleteFromServer(item: LibraryItem) {
        val record = item.record ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                c.originals.deleteFromServer(record)
                say(R.string.viewer_deleted_from_server)
            } catch (e: Exception) {
                sayError(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Frees space: removes the phone copy of a photo that is safe on the server. */
    fun removeFromPhone(item: LibraryItem) {
        val record = item.record ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                val candidate = c.freeSpace.candidateFor(record) ?: return@launch
                val verified = c.freeSpace.verify(listOf(candidate)) { _, _ -> }
                if (verified.isEmpty()) {
                    say(R.string.viewer_not_verified)
                    return@launch
                }
                deleteFromDevice(listOf(candidate.media), AfterDeletion(verified, alsoFromServer = null))
            } catch (e: Exception) {
                sayError(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Deletes the photo from the phone; with [everywhere] also from the server. */
    fun delete(item: LibraryItem, everywhere: Boolean) {
        val media = item.device
        val record = item.record
        if (media == null) {
            if (everywhere) deleteFromServer(item)
            return
        }
        viewModelScope.launch {
            deleteFromDevice(listOf(media), AfterDeletion(emptyList(), alsoFromServer = record.takeIf { everywhere }))
        }
    }

    private suspend fun deleteFromDevice(media: List<DeviceMedia>, after: AfterDeletion) {
        afterDeletion = after
        if (Build.VERSION.SDK_INT >= 30) {
            val app = getApplication<Application>()
            val request = MediaStore.createDeleteRequest(app.contentResolver, media.map { it.uri })
            _deleteRequest.value = SystemDeleteRequest(request.intentSender)
        } else {
            c.mediaStore.deleteDirectly(media.map { it.uri })
            onDeleteResult(accepted = true)
        }
    }

    fun onDeleteRequestShown() {
        _deleteRequest.value = null
    }

    fun onDeleteResult(accepted: Boolean) {
        val after = afterDeletion ?: return
        afterDeletion = null
        if (!accepted) return
        viewModelScope.launch {
            try {
                if (after.freed.isNotEmpty()) {
                    val result = c.freeSpace.finish(after.freed)
                    if (result.count > 0) say(R.string.viewer_removed_from_phone)
                }
                after.alsoFromServer?.let { c.originals.deleteFromServer(it) }
                c.library.refresh()
            } catch (e: Exception) {
                sayError(e)
            }
        }
    }
}
