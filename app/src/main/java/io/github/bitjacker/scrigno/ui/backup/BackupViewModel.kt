package io.github.bitjacker.scrigno.ui.backup

import android.app.Application
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.backup.BackupState
import io.github.bitjacker.scrigno.backup.FreeCandidate
import io.github.bitjacker.scrigno.backup.FreeSpaceManager
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.library.Library
import io.github.bitjacker.scrigno.data.library.Location
import io.github.bitjacker.scrigno.data.settings.AppSettings
import io.github.bitjacker.scrigno.data.settings.LastBackup
import io.github.bitjacker.scrigno.ui.common.SystemDeleteRequest
import io.github.bitjacker.scrigno.util.ErrorMessages
import io.github.bitjacker.scrigno.util.Formatters
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupUiState(
    val server: ServerConfig? = null,
    val deviceFolder: String = "",
    val settings: AppSettings = AppSettings(),
    val lastBackup: LastBackup? = null,
    val status: BackupState = BackupState.Idle,
    val waitingForNetwork: Boolean = false,
    val library: Library = Library(),
    val pendingCount: Int = 0,
    /** Null while being computed. */
    val freeCandidates: List<FreeCandidate>? = null,
) {
    val safeCount: Int get() = library.items.count { it.record != null }
}

sealed interface FreeingState {
    data object Idle : FreeingState
    data class Verifying(val done: Int, val total: Int) : FreeingState
    data object Deleting : FreeingState
}

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val c = application.container
    private val candidates = MutableStateFlow<List<FreeCandidate>?>(null)

    private val _freeing = MutableStateFlow<FreeingState>(FreeingState.Idle)
    val freeing: StateFlow<FreeingState> = _freeing.asStateFlow()

    private val _deleteRequest = MutableStateFlow<SystemDeleteRequest?>(null)
    val deleteRequest: StateFlow<SystemDeleteRequest?> = _deleteRequest.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var inFlight: List<FreeCandidate>? = null

    private val base = combine(
        c.settings.settings,
        c.settings.server,
        c.settings.lastBackup,
        c.backupStatus.state,
        c.library.library,
    ) { settings, server, last, status, library ->
        BackupUiState(
            server = server,
            deviceFolder = c.settings.deviceFolder,
            settings = settings,
            lastBackup = last,
            status = status,
            library = library,
            pendingCount = library.items.count { item ->
                val media = item.device
                item.location == Location.DEVICE && media != null &&
                    (settings.includeVideos || !media.isVideo) &&
                    (settings.allFolders || media.bucketId in settings.selectedFolders)
            },
        )
    }

    val state: StateFlow<BackupUiState> = combine(base, c.scheduler.manualBackupWaiting(), candidates) { state, waiting, free ->
        state.copy(waitingForNetwork = waiting && state.status is BackupState.Idle, freeCandidates = free)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    init {
        viewModelScope.launch {
            combine(
                c.library.library,
                c.settings.settings.map { it.keepOnDeviceDays }.distinctUntilChanged(),
                c.settings.server,
            ) { _, days, _ -> days }.collectLatest { days ->
                candidates.value = if (days < 0) {
                    emptyList()
                } else {
                    runCatching { c.freeSpace.candidates(days) }.getOrDefault(emptyList())
                }
            }
        }
    }

    fun backupNow() {
        c.scheduler.runNow()
    }

    fun stop() {
        c.backupStatus.requestStop()
    }

    fun setAutoBackup(enabled: Boolean) {
        c.settings.update { it.copy(autoBackup = enabled) }
        c.scheduler.apply(c.settings.settings.value, c.settings.server.value, replace = true)
    }

    fun setKeepDays(days: Int) {
        c.settings.update { it.copy(keepOnDeviceDays = days) }
    }

    /** Verifies on the server, then asks Android to delete the phone copies. */
    fun freeUpSpace() {
        val items = candidates.value.orEmpty().take(FreeSpaceManager.MAX_PER_REQUEST)
        if (items.isEmpty() || _freeing.value != FreeingState.Idle) return
        viewModelScope.launch {
            try {
                _freeing.value = FreeingState.Verifying(0, items.size)
                val verified = c.freeSpace.verify(items) { done, total -> _freeing.value = FreeingState.Verifying(done, total) }
                if (verified.isEmpty()) {
                    say(R.string.free_space_nothing_verified)
                    _freeing.value = FreeingState.Idle
                    return@launch
                }
                inFlight = verified
                _freeing.value = FreeingState.Deleting
                if (Build.VERSION.SDK_INT >= 30) {
                    val request = c.freeSpace.deleteRequest(verified)
                    if (request == null) {
                        inFlight = null
                        _freeing.value = FreeingState.Idle
                    } else {
                        _deleteRequest.value = SystemDeleteRequest(request.intentSender)
                    }
                } else {
                    c.freeSpace.deleteDirectly(verified)
                    onDeleteResult(accepted = true)
                }
            } catch (e: Exception) {
                inFlight = null
                _freeing.value = FreeingState.Idle
                _messages.tryEmit(ErrorMessages.describe(getApplication(), e))
            }
        }
    }

    fun onDeleteRequestShown() {
        _deleteRequest.value = null
    }

    fun onDeleteResult(accepted: Boolean) {
        val items = inFlight
        inFlight = null
        if (items == null || !accepted) {
            _freeing.value = FreeingState.Idle
            return
        }
        viewModelScope.launch {
            try {
                val result = c.freeSpace.finish(items)
                say(R.string.free_space_done, Formatters.size(getApplication(), result.bytes))
            } catch (e: Exception) {
                _messages.tryEmit(ErrorMessages.describe(getApplication(), e))
            } finally {
                _freeing.value = FreeingState.Idle
            }
        }
    }

    private fun say(@StringRes message: Int, vararg args: Any) {
        _messages.tryEmit(getApplication<Application>().getString(message, *args))
    }
}
