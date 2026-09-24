package io.github.bitjacker.scrigno.data.remote

import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.RemoteStorage
import io.github.bitjacker.scrigno.core.remote.RemoteStorageFactory
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A connection to the server shared by the gallery (downloads) and the "free up space" checks.
 * Opened on demand, reused while browsing, closed after a minute of inactivity.
 */
class RemoteSessions(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var storage: RemoteStorage? = null
    private var openedWith: ServerConfig? = null
    private var idleJob: Job? = null

    suspend fun <T> withStorage(block: (RemoteStorage) -> T): T = mutex.withLock {
        idleJob?.cancel()
        try {
            withContext(Dispatchers.IO) {
                val config = settings.server.value ?: throw RemoteException("No server configured")
                val current = storage?.takeIf { openedWith == config && it.isConnected } ?: open(config)
                try {
                    block(current)
                } catch (e: Exception) {
                    // The connection may be in a bad state: start from scratch next time.
                    closeQuietly()
                    throw e
                }
            }
        } finally {
            idleJob = scope.launch {
                delay(IDLE_MILLIS)
                mutex.withLock { closeQuietly() }
            }
        }
    }

    private fun open(config: ServerConfig): RemoteStorage {
        closeQuietly()
        val opened = RemoteStorageFactory.create(config)
        opened.connect()
        settings.pinFingerprintIfNew(config, opened.serverFingerprint)
        storage = opened
        // Pinning the fingerprint changes the saved configuration: remember the updated one.
        openedWith = settings.server.value?.takeIf { it.identity() == config.identity() } ?: config
        return opened
    }

    private fun closeQuietly() {
        storage?.let { runCatching { it.close() } }
        storage = null
        openedWith = null
    }

    private companion object {
        const val IDLE_MILLIS = 60_000L
    }
}
