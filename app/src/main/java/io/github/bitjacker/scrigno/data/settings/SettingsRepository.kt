package io.github.bitjacker.scrigno.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Preferences and server configuration, kept in the app's private storage. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("scrigno", Context.MODE_PRIVATE)
    private val secrets = SecretBox()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _server = MutableStateFlow(readServer())
    val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    private val _lastBackup = MutableStateFlow(readLastBackup())
    val lastBackup: StateFlow<LastBackup?> = _lastBackup.asStateFlow()

    /** Name of the folder, on the server, that holds this phone's photos. */
    val deviceFolder: String
        get() = RemotePaths.sanitizeFileName(settings.value.deviceFolder.ifBlank { defaultDeviceFolder() })

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        writeSettings(updated)
        _settings.value = updated
    }

    @Synchronized
    fun saveServer(config: ServerConfig?) {
        prefs.edit().apply {
            if (config == null) {
                SERVER_KEYS.forEach { remove(it) }
            } else {
                putString(K_PROTOCOL, config.protocol.name)
                putString(K_HOST, config.host)
                putInt(K_PORT, config.port)
                putString(K_USER, config.username)
                putString(K_PASSWORD, secrets.encrypt(config.password))
                putString(K_BASE, config.basePath)
                putString(K_SHARE, config.share)
                putString(K_DOMAIN, config.domain)
                putString(K_FINGERPRINT, config.trustedFingerprint)
                putBoolean(K_SELF_SIGNED, config.allowSelfSigned)
            }
        }.apply()
        _server.value = config
    }

    /** Remembers the identity shown by the server on the first connection (trust on first use). */
    @Synchronized
    fun pinFingerprintIfNew(used: ServerConfig, fingerprint: String?) {
        if (fingerprint.isNullOrEmpty() || used.trustedFingerprint.isNotEmpty()) return
        val current = _server.value ?: return
        if (current.identity() != used.identity() || current.trustedFingerprint.isNotEmpty()) return
        saveServer(current.copy(trustedFingerprint = fingerprint))
    }

    fun recordBackup(result: LastBackup) {
        prefs.edit()
            .putLong(K_LAST_TIME, result.finishedAt)
            .putInt(K_LAST_UPLOADED, result.uploaded)
            .putInt(K_LAST_FAILED, result.failed)
            .putString(K_LAST_ERROR, result.error)
            .apply()
        _lastBackup.value = result
    }

    /** Forgets everything: server, password key, preferences. */
    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
        secrets.forget()
        _settings.value = AppSettings()
        _server.value = null
        _lastBackup.value = null
    }

    fun defaultDeviceFolder(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = when {
            model.isEmpty() -> "Phone"
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) } + " " + model
        }
        return RemotePaths.sanitizeFileName(name)
    }

    private fun readSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            onboardingDone = prefs.getBoolean(K_ONBOARDING, defaults.onboardingDone),
            autoBackup = prefs.getBoolean(K_AUTO, defaults.autoBackup),
            intervalHours = prefs.getInt(K_INTERVAL, defaults.intervalHours),
            preferredHour = prefs.getInt(K_HOUR, defaults.preferredHour),
            wifiOnly = prefs.getBoolean(K_WIFI, defaults.wifiOnly),
            chargingOnly = prefs.getBoolean(K_CHARGING, defaults.chargingOnly),
            includeVideos = prefs.getBoolean(K_VIDEOS, defaults.includeVideos),
            allFolders = prefs.getBoolean(K_ALL_FOLDERS, defaults.allFolders),
            selectedFolders = prefs.getStringSet(K_FOLDERS, emptySet()).orEmpty().toSet(),
            keepOnDeviceDays = prefs.getInt(K_KEEP_DAYS, defaults.keepOnDeviceDays),
            freeSpaceReminder = prefs.getBoolean(K_REMINDER, defaults.freeSpaceReminder),
            cacheLimitMb = prefs.getInt(K_CACHE, defaults.cacheLimitMb),
            deviceFolder = prefs.getString(K_DEVICE_FOLDER, defaults.deviceFolder).orEmpty(),
        )
    }

    private fun writeSettings(s: AppSettings) {
        prefs.edit()
            .putBoolean(K_ONBOARDING, s.onboardingDone)
            .putBoolean(K_AUTO, s.autoBackup)
            .putInt(K_INTERVAL, s.intervalHours)
            .putInt(K_HOUR, s.preferredHour)
            .putBoolean(K_WIFI, s.wifiOnly)
            .putBoolean(K_CHARGING, s.chargingOnly)
            .putBoolean(K_VIDEOS, s.includeVideos)
            .putBoolean(K_ALL_FOLDERS, s.allFolders)
            .putStringSet(K_FOLDERS, s.selectedFolders.toSet())
            .putInt(K_KEEP_DAYS, s.keepOnDeviceDays)
            .putBoolean(K_REMINDER, s.freeSpaceReminder)
            .putInt(K_CACHE, s.cacheLimitMb)
            .putString(K_DEVICE_FOLDER, s.deviceFolder)
            .apply()
    }

    private fun readServer(): ServerConfig? {
        val protocol = Protocol.fromName(prefs.getString(K_PROTOCOL, null)) ?: return null
        val host = prefs.getString(K_HOST, null) ?: return null
        return ServerConfig(
            protocol = protocol,
            host = host,
            port = prefs.getInt(K_PORT, protocol.defaultPort),
            username = prefs.getString(K_USER, "").orEmpty(),
            password = secrets.decrypt(prefs.getString(K_PASSWORD, null)).orEmpty(),
            basePath = prefs.getString(K_BASE, "").orEmpty(),
            share = prefs.getString(K_SHARE, "").orEmpty(),
            domain = prefs.getString(K_DOMAIN, "").orEmpty(),
            trustedFingerprint = prefs.getString(K_FINGERPRINT, "").orEmpty(),
            allowSelfSigned = prefs.getBoolean(K_SELF_SIGNED, false),
        )
    }

    private fun readLastBackup(): LastBackup? {
        val time = prefs.getLong(K_LAST_TIME, 0L)
        if (time == 0L) return null
        return LastBackup(
            finishedAt = time,
            uploaded = prefs.getInt(K_LAST_UPLOADED, 0),
            failed = prefs.getInt(K_LAST_FAILED, 0),
            error = prefs.getString(K_LAST_ERROR, null),
        )
    }

    private companion object {
        const val K_ONBOARDING = "onboarding_done"
        const val K_AUTO = "auto_backup"
        const val K_INTERVAL = "interval_hours"
        const val K_HOUR = "preferred_hour"
        const val K_WIFI = "wifi_only"
        const val K_CHARGING = "charging_only"
        const val K_VIDEOS = "include_videos"
        const val K_ALL_FOLDERS = "all_folders"
        const val K_FOLDERS = "selected_folders"
        const val K_KEEP_DAYS = "keep_on_device_days"
        const val K_REMINDER = "free_space_reminder"
        const val K_CACHE = "cache_limit_mb"
        const val K_DEVICE_FOLDER = "device_folder"

        const val K_PROTOCOL = "server_protocol"
        const val K_HOST = "server_host"
        const val K_PORT = "server_port"
        const val K_USER = "server_user"
        const val K_PASSWORD = "server_password"
        const val K_BASE = "server_base_path"
        const val K_SHARE = "server_share"
        const val K_DOMAIN = "server_domain"
        const val K_FINGERPRINT = "server_fingerprint"
        const val K_SELF_SIGNED = "server_self_signed"
        val SERVER_KEYS = listOf(
            K_PROTOCOL, K_HOST, K_PORT, K_USER, K_PASSWORD, K_BASE, K_SHARE, K_DOMAIN, K_FINGERPRINT, K_SELF_SIGNED,
        )

        const val K_LAST_TIME = "last_backup_time"
        const val K_LAST_UPLOADED = "last_backup_uploaded"
        const val K_LAST_FAILED = "last_backup_failed"
        const val K_LAST_ERROR = "last_backup_error"
    }
}
