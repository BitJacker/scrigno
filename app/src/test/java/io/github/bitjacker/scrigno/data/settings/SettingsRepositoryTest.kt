package io.github.bitjacker.scrigno.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: SettingsRepository

    private val server = ServerConfig(
        protocol = Protocol.SFTP,
        host = "nas.local",
        port = 2222,
        username = "mario",
        password = "correct horse battery staple",
        basePath = "/srv/photos",
    )

    @Before
    fun setUp() {
        SettingsRepository(context).reset()
        settings = SettingsRepository(context)
    }

    @Test
    fun defaultsArePrivacyFriendly() {
        val defaults = settings.settings.value
        assertFalse(defaults.onboardingDone)
        assertTrue(defaults.wifiOnly)
        assertEquals(AppSettings.NEVER_REMOVE, defaults.keepOnDeviceDays)
        assertNull(settings.server.value)
    }

    @Test
    fun settingsSurviveARestart() {
        settings.update { it.copy(intervalHours = 12, preferredHour = 4, includeVideos = false, keepOnDeviceDays = 30) }
        val reloaded = SettingsRepository(context).settings.value
        assertEquals(12, reloaded.intervalHours)
        assertEquals(4, reloaded.preferredHour)
        assertFalse(reloaded.includeVideos)
        assertEquals(30, reloaded.keepOnDeviceDays)
    }

    @Test
    fun serverAndPasswordAreSavedButThePasswordIsNotStoredInClear() {
        settings.saveServer(server)
        assertEquals(server, SettingsRepository(context).server.value)

        val raw = context.getSharedPreferences("scrigno", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("the password must not appear as plain text", raw.contains(server.password))
    }

    @Test
    fun theFingerprintIsPinnedOnlyOnce() {
        settings.saveServer(server)
        settings.pinFingerprintIfNew(server, "SHA256:first")
        assertEquals("SHA256:first", settings.server.value!!.trustedFingerprint)

        settings.pinFingerprintIfNew(server, "SHA256:second")
        assertEquals("SHA256:first", settings.server.value!!.trustedFingerprint)

        // A connection made with another configuration never pins on the saved one.
        settings.saveServer(server.copy(trustedFingerprint = ""))
        settings.pinFingerprintIfNew(server.copy(host = "other.host"), "SHA256:other")
        assertEquals("", settings.server.value!!.trustedFingerprint)
    }

    @Test
    fun lastBackupIsRemembered() {
        settings.recordBackup(LastBackup(finishedAt = 123L, uploaded = 4, failed = 1, error = "boom"))
        assertEquals(LastBackup(123L, 4, 1, "boom"), SettingsRepository(context).lastBackup.value)
    }

    @Test
    fun resetForgetsEverything() {
        settings.saveServer(server)
        settings.update { it.copy(onboardingDone = true) }
        settings.reset()
        val fresh = SettingsRepository(context)
        assertNull(fresh.server.value)
        assertFalse(fresh.settings.value.onboardingDone)
    }

    @Test
    fun deviceFolderHasASafeDefault() {
        val folder = settings.deviceFolder
        assertTrue(folder.isNotBlank())
        assertFalse(folder.contains('/'))
        settings.update { it.copy(deviceFolder = "Phone of Mario") }
        assertEquals("Phone of Mario", settings.deviceFolder)
        assertNotEquals("", settings.defaultDeviceFolder())
    }
}
