package io.github.bitjacker.scrigno.core.remote.sftp

import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteStorage
import io.github.bitjacker.scrigno.core.remote.RemoteStorageContract
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.core.remote.ServerIdentityChangedException
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SftpStorageTest : RemoteStorageContract() {

    private lateinit var server: SshServer
    private lateinit var root: Path

    @Before
    fun start() {
        root = Files.createTempDirectory("scrigno-sftp")
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolveSibling("${root.fileName}-hostkey.ser"))
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
    }

    @After
    fun stop() {
        server.stop(true)
        root.toFile().deleteRecursively()
    }

    private fun config(password: String = PASSWORD, pinned: String = "") = ServerConfig(
        protocol = Protocol.SFTP,
        host = "127.0.0.1",
        port = server.port,
        username = USER,
        password = password,
        basePath = "backup",
        trustedFingerprint = pinned,
    )

    override fun storage(): RemoteStorage = SftpStorage(config())

    override fun storageWithWrongPassword(): RemoteStorage = SftpStorage(config(password = "wrong"))

    @Test
    fun hostKeyIsPinnedOnFirstUseAndCheckedAfterwards() {
        val fingerprint = SftpStorage(config()).use { storage ->
            storage.connect()
            storage.serverFingerprint
        }
        assertNotNull(fingerprint)
        assertTrue(fingerprint!!.startsWith("SHA256:"))

        SftpStorage(config(pinned = fingerprint)).use { storage ->
            storage.connect()
            assertEquals(fingerprint, storage.serverFingerprint)
        }

        val error = assertThrows(ServerIdentityChangedException::class.java) {
            SftpStorage(config(pinned = "SHA256:somebody-else")).use { it.connect() }
        }
        assertEquals(fingerprint, error.actual)
    }

    private companion object {
        const val USER = "alice"
        const val PASSWORD = "s3cret è"
    }
}
