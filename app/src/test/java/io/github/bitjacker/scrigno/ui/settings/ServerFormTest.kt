package io.github.bitjacker.scrigno.ui.settings

import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormTest {

    @Test
    fun validation() {
        assertFalse(ServerForm().isValid)
        assertTrue(ServerForm(host = "192.168.1.10").isValid)
        assertFalse(ServerForm(host = "nas", port = "0").isValid)
        assertFalse(ServerForm(host = "nas", port = "70000").isValid)
        assertFalse(ServerForm(protocol = Protocol.SMB, host = "nas", port = "445").isValid)
        assertTrue(ServerForm(protocol = Protocol.SMB, host = "nas", port = "445", share = "photos").isValid)
        // A pasted smb:// URL contains the share.
        assertTrue(ServerForm(protocol = Protocol.SMB, host = "smb://nas/photos", port = "445").isValid)
    }

    @Test
    fun aPastedUrlFillsTheFields() {
        val config = ServerForm(
            protocol = Protocol.WEBDAV,
            host = "https://cloud.example.com/remote.php/dav/files/mario",
            port = "80",
            username = "mario",
            password = "pw",
        ).toConfig()
        assertEquals(Protocol.WEBDAVS, config.protocol)
        assertEquals("cloud.example.com", config.host)
        assertEquals(443, config.port)
        assertEquals("/remote.php/dav/files/mario", config.basePath)
    }

    @Test
    fun roundTrip() {
        val config = ServerConfig(
            protocol = Protocol.SMB,
            host = "nas",
            port = 445,
            username = "mario",
            password = "pw",
            basePath = "Phone",
            share = "photos",
            domain = "WORKGROUP",
            trustedFingerprint = "",
            allowSelfSigned = false,
        )
        assertEquals(config, ServerForm.from(config).toConfig())
        assertEquals(ServerForm(), ServerForm.from(null))
    }
}
