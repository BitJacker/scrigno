package io.github.bitjacker.scrigno.core.remote

import org.junit.Assume.assumeTrue
import org.junit.Before
import java.util.UUID

/**
 * Runs the contract against a real server of your choice (skipped unless configured), e.g.:
 *
 * ```
 * ./gradlew :core:test --tests '*ExternalServerTest' -Dscrigno.it.protocol=SMB \
 *     -Dscrigno.it.host=192.168.1.10 -Dscrigno.it.share=photos -Dscrigno.it.user=me -Dscrigno.it.password=...
 * ```
 */
class ExternalServerTest : RemoteStorageContract() {

    private fun property(name: String): String? = System.getProperty("scrigno.it.$name")?.takeIf { it.isNotEmpty() }

    // A fresh folder for every test, removed by nobody: point it to a scratch location.
    private val base = RemotePathsForTests.join(property("basePath").orEmpty(), "scrigno-it-" + UUID.randomUUID())

    @Before
    fun requireServer() {
        assumeTrue("Set -Dscrigno.it.host (and friends) to run this test", property("host") != null)
    }

    private fun config(password: String?): ServerConfig {
        val protocol = Protocol.valueOf(property("protocol") ?: "SFTP")
        return ServerConfig(
            protocol = protocol,
            host = property("host")!!,
            port = property("port")?.toInt() ?: protocol.defaultPort,
            username = property("user").orEmpty(),
            password = password ?: property("password").orEmpty(),
            basePath = base,
            share = property("share").orEmpty(),
            domain = property("domain").orEmpty(),
            allowSelfSigned = property("allowSelfSigned") == "true",
        )
    }

    override fun storage(): RemoteStorage = RemoteStorageFactory.create(config(null))

    override fun storageWithWrongPassword(): RemoteStorage {
        assumeTrue("The server allows anonymous access", property("user") != null)
        return RemoteStorageFactory.create(config("definitely-not-the-password"))
    }
}

private typealias RemotePathsForTests = io.github.bitjacker.scrigno.core.backup.RemotePaths
