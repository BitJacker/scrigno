package io.github.bitjacker.scrigno.core.remote.ftp

import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteStorage
import io.github.bitjacker.scrigno.core.remote.RemoteStorageContract
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * FTPS (explicit TLS) against an embedded server with a self-signed certificate. When the server
 * offers TLS 1.2, uploads use it (see [ScrignoFtpsClient]).
 */
open class FtpsStorageTest : RemoteStorageContract() {

    /** TLS versions the server accepts. */
    protected open val serverTls = arrayOf("TLSv1.2", "TLSv1.3")

    /** TLS version the data connections must use. */
    protected open val expectedDataTls = "TLSv1.2"

    private lateinit var server: FtpServer
    private lateinit var root: File
    private var port = 0

    @Before
    fun start() {
        root = Files.createTempDirectory("scrigno-ftps").toFile()
        port = ServerSocket(0).use { it.localPort }
        val ssl = SslConfigurationFactory().apply {
            keystoreFile = keystore
            keystorePassword = KEYSTORE_PASSWORD
            keystoreType = "PKCS12"
            setSslProtocol(*serverTls)
        }.createSslConfiguration()
        val factory = FtpServerFactory()
        factory.addListener(
            "default",
            ListenerFactory().apply {
                serverAddress = "127.0.0.1"
                port = this@FtpsStorageTest.port
                sslConfiguration = ssl
                dataConnectionConfiguration = DataConnectionConfigurationFactory()
                    .apply { sslConfiguration = ssl }
                    .createDataConnectionConfiguration()
            }.createListener(),
        )
        val users = PropertiesUserManagerFactory().apply { passwordEncryptor = ClearTextPasswordEncryptor() }.createUserManager()
        users.save(
            BaseUser().apply {
                name = USER
                password = PASSWORD
                homeDirectory = root.absolutePath
                authorities = listOf<Authority>(WritePermission())
            },
        )
        factory.userManager = users
        server = factory.createServer()
        server.start()
    }

    @After
    fun stop() {
        server.stop()
        root.deleteRecursively()
    }

    private fun config(password: String = PASSWORD) = ServerConfig(
        protocol = Protocol.FTPS,
        host = "127.0.0.1",
        port = port,
        username = USER,
        password = password,
        basePath = "photos",
        allowSelfSigned = true,
    )

    override fun storage(): RemoteStorage = FtpStorage(config())

    override fun storageWithWrongPassword(): RemoteStorage = FtpStorage(config(password = "wrong"))

    @Test
    fun uploadsUseTheExpectedTlsVersion() {
        val bytes = ByteArray(10_000) { it.toByte() }
        FtpStorage(config()).use { storage ->
            storage.connect()
            storage.mkdirs("")
            storage.upload("tls.bin", ByteArrayInputStream(bytes), bytes.size.toLong())
            assertEquals(expectedDataTls, storage.dataTlsVersion)
            assertEquals(bytes.size.toLong(), storage.stat("tls.bin")?.size)
        }
    }

    companion object {
        const val USER = "carol"
        const val PASSWORD = "s3cret-pw"
        const val KEYSTORE_PASSWORD = "changeit"

        /** A self-signed certificate, made once with the JDK's keytool. */
        val keystore: File by lazy {
            val file = File.createTempFile("scrigno-ftps", ".p12").apply {
                delete()
                deleteOnExit()
            }
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "ftps", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", file.path, "-storepass", KEYSTORE_PASSWORD, "-keypass", KEYSTORE_PASSWORD,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            file
        }
    }
}

/** A server that accepts TLS 1.3 only: the client falls back to it, and uploads still end cleanly. */
class FtpsTls13OnlyStorageTest : FtpsStorageTest() {
    override val serverTls = arrayOf("TLSv1.3")
    override val expectedDataTls = "TLSv1.3"
}
