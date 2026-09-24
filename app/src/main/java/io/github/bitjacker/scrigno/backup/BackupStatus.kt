package io.github.bitjacker.scrigno.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BackupState {
    data object Idle : BackupState

    data class Running(
        val done: Int,
        val total: Int,
        val currentName: String?,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : BackupState {
        val fraction: Float
            get() = when {
                bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
                total > 0 -> done.toFloat() / total
                else -> 0f
            }
    }
}

/** Live progress of the backup running in this process, observed by the UI. */
class BackupStatus {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    @Volatile
    var stopRequested: Boolean = false
        private set

    fun update(state: BackupState) {
        _state.value = state
    }

    fun requestStop() {
        stopRequested = true
    }

    fun clearStop() {
        stopRequested = false
    }
}
