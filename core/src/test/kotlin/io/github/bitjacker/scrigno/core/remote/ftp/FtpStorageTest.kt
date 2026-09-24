package io.github.bitjacker.scrigno.core.remote.ftp

import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteStorage
import io.github.bitjacker.scrigno.core.remote.RemoteStorageContract
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Before
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class FtpStorageTest : RemoteStorageContract() {

    private lateinit var server: FtpServer
    private lateinit var root: File
    private var port = 0

    @Before
    fun start() {
        root = Files.createTempDirectory("scrigno-ftp").toFile()
        port = ServerSocket(0).use { it.localPort }
        val factory = FtpServerFactory()
        factory.addListener(
            "default",
            ListenerFactory().apply {
                serverAddress = "127.0.0.1"
                port = this@FtpStorageTest.port
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
        protocol = Protocol.FTP,
        host = "127.0.0.1",
        port = port,
        username = USER,
        password = password,
        basePath = "photos",
    )

    override fun storage(): RemoteStorage = FtpStorage(config())

    override fun storageWithWrongPassword(): RemoteStorage = FtpStorage(config(password = "wrong"))

    private companion object {
        const val USER = "bob"
        const val PASSWORD = "pa55word"
    }
}
