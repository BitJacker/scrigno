package io.github.bitjacker.scrigno.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteStorageFactory
import io.github.bitjacker.scrigno.core.remote.ServerAddress
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** What the user is typing in the server form. */
data class ServerForm(
    val protocol: Protocol = Protocol.SFTP,
    val host: String = "",
    val port: String = Protocol.SFTP.defaultPort.toString(),
    val username: String = "",
    val password: String = "",
    val basePath: String = "",
    val share: String = "",
    val domain: String = "",
    val allowSelfSigned: Boolean = false,
    val fingerprint: String = "",
) {
    val isValid: Boolean
        get() = host.isNotBlank() &&
            (port.toIntOrNull() ?: 0) in 1..65535 &&
            (protocol != Protocol.SMB || share.isNotBlank() || host.contains('/'))

    fun toConfig(): ServerConfig = ServerAddress.normalize(
        ServerConfig(
            protocol = protocol,
            host = host,
            port = port.toIntOrNull() ?: protocol.defaultPort,
            username = username,
            password = password,
            basePath = basePath,
            share = share,
            domain = domain,
            trustedFingerprint = fingerprint,
            allowSelfSigned = allowSelfSigned,
        ),
    )

    companion object {
        fun from(config: ServerConfig?): ServerForm = if (config == null) {
            ServerForm()
        } else {
            ServerForm(
                protocol = config.protocol,
                host = config.host,
                port = config.port.toString(),
                username = config.username,
                password = config.password,
                basePath = config.basePath,
                share = config.share,
                domain = config.domain,
                allowSelfSigned = config.allowSelfSigned,
                fingerprint = config.trustedFingerprint,
            )
        }
    }
}

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest

    /** @property newFingerprint identity seen for the first time, to show to the user. */
    data class Success(val newFingerprint: String?) : ConnectionTest
    data class Failure(val message: String) : ConnectionTest
}

class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val c = application.container

    private val _form = MutableStateFlow(ServerForm.from(c.settings.server.value))
    val form: StateFlow<ServerForm> = _form.asStateFlow()

    private val _test = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val test: StateFlow<ConnectionTest> = _test.asStateFlow()

    fun edit(transform: (ServerForm) -> ServerForm) {
        _form.update { old ->
            val new = transform(old)
            // Another address means another server: its identity must be learned again.
            if (new.host.trim() != old.host.trim() || new.port != old.port) new.copy(fingerprint = "") else new
        }
        _test.value = ConnectionTest.Idle
    }

    fun setProtocol(protocol: Protocol) = edit { form ->
        val defaultPort = form.port.isBlank() || form.port.toIntOrNull() == form.protocol.defaultPort
        form.copy(
            protocol = protocol,
            port = if (defaultPort) protocol.defaultPort.toString() else form.port,
            // Another protocol means another key/certificate.
            fingerprint = "",
        )
    }

    fun forgetFingerprint() = edit { it.copy(fingerprint = "") }

    /** Connects, logs in, creates the folder and writes (then deletes) a small test file. */
    fun testConnection(onSuccess: (ServerConfig) -> Unit = {}) {
        if (_test.value == ConnectionTest.Running) return
        val config = _form.value.toConfig()
        viewModelScope.launch {
            _test.value = ConnectionTest.Running
            val result = withContext(Dispatchers.IO) { runCatching { check(config) } }
            result.onSuccess { fingerprint ->
                val pinned = if (config.trustedFingerprint.isEmpty() && fingerprint != null) fingerprint else config.trustedFingerprint
                val verified = config.copy(trustedFingerprint = pinned)
                _form.value = ServerForm.from(verified)
                _test.value = ConnectionTest.Success(newFingerprint = fingerprint.takeIf { config.trustedFingerprint.isEmpty() })
                onSuccess(verified)
            }.onFailure { error ->
                _test.value = ConnectionTest.Failure(ErrorMessages.describe(getApplication(), error))
            }
        }
    }

    private fun check(config: ServerConfig): String? = RemoteStorageFactory.create(config).use { storage ->
        storage.connect()
        storage.mkdirs("")
        val probe = "scrigno-write-test.txt"
        val bytes = "Scrigno write test".toByteArray()
        storage.upload(probe, ByteArrayInputStream(bytes), bytes.size.toLong())
        storage.delete(probe)
        storage.serverFingerprint
    }

    /** Tests the connection and, if it works, saves the configuration. */
    fun save(onSaved: () -> Unit) = testConnection { config ->
        store(config)
        onSaved()
    }

    /** Saves even though the test failed (e.g. the server is off right now). */
    fun saveAnyway(onSaved: () -> Unit) {
        store(_form.value.toConfig())
        onSaved()
    }

    private fun store(config: ServerConfig) {
        c.settings.saveServer(config)
        c.scheduler.apply(c.settings.settings.value, config, replace = true)
        viewModelScope.launch { c.library.refresh() }
    }
}
