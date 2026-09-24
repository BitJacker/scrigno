package io.github.bitjacker.scrigno.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.settings.AppSettings
import io.github.bitjacker.scrigno.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val c = application.container

    val settings: StateFlow<AppSettings> = c.settings.settings
    val server: StateFlow<ServerConfig?> = c.settings.server

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    private val _rebuilding = MutableStateFlow(false)
    val rebuilding: StateFlow<Boolean> = _rebuilding.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val defaultDeviceFolder: String get() = c.settings.defaultDeviceFolder()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) { _cacheSize.value = c.originals.cacheSize() }
    }

    /** Saves a change; [reschedule] when it affects when the automatic backup runs. */
    fun update(reschedule: Boolean = false, transform: (AppSettings) -> AppSettings) {
        c.settings.update(transform)
        if (reschedule) c.scheduler.apply(c.settings.settings.value, c.settings.server.value, replace = true)
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            c.originals.clearCache()
            _cacheSize.value = c.originals.cacheSize()
        }
    }

    fun rebuildIndex() {
        if (_rebuilding.value) return
        viewModelScope.launch {
            _rebuilding.value = true
            try {
                val found = c.originals.rebuildIndex()
                val app = getApplication<Application>()
                _messages.tryEmit(app.resources.getQuantityString(R.plurals.rebuild_done, found, found))
            } catch (e: Exception) {
                _messages.tryEmit(ErrorMessages.describe(getApplication(), e))
            } finally {
                _rebuilding.value = false
            }
        }
    }

    /** Forgets the server, the settings and the local index. Files on the server are untouched. */
    fun resetApp(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                c.scheduler.cancelAll()
                c.database.clear()
                c.thumbnails.clear()
                c.originals.clearCache()
                c.settings.reset()
                c.library.refresh()
            }
            onDone()
        }
    }
}
