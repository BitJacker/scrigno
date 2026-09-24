package io.github.bitjacker.scrigno.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    @Test
    fun plainHosts() {
        assertEquals(ServerAddress.Parsed("192.168.1.10", null, null, null), ServerAddress.parse(" 192.168.1.10 "))
        assertEquals(ServerAddress.Parsed("nas.local", 2222, null, null), ServerAddress.parse("nas.local:2222"))
        assertEquals(ServerAddress.Parsed("fd00::1", 22, null, null), ServerAddress.parse("[fd00::1]:22"))
        assertEquals(ServerAddress.Parsed("fd00::1", null, null, null), ServerAddress.parse("fd00::1"))
    }

    @Test
    fun urls() {
        val nextcloud = ServerAddress.parse("https://cloud.example.com/remote.php/dav/files/me/")
        assertEquals("cloud.example.com", nextcloud.host)
        assertEquals(Protocol.WEBDAVS, nextcloud.protocol)
        assertEquals("/remote.php/dav/files/me", nextcloud.path)
        assertNull(nextcloud.port)

        val sftp = ServerAddress.parse("sftp://mario@nas.home.arpa:2022/srv/photos")
        assertEquals(ServerAddress.Parsed("nas.home.arpa", 2022, "/srv/photos", Protocol.SFTP), sftp)
    }

    @Test
    fun normalizeSplitsUrlsIntoFields() {
        val smb = ServerAddress.normalize(ServerConfig(Protocol.SFTP, host = "smb://nas/Photos/Phone"))
        assertEquals(Protocol.SMB, smb.protocol)
        assertEquals("nas", smb.host)
        assertEquals(445, smb.port)
        assertEquals("Photos", smb.share)
        assertEquals("Phone", smb.basePath)

        val dav = ServerAddress.normalize(ServerConfig(Protocol.WEBDAV, host = "https://cloud.example.com:8443/dav"))
        assertEquals(Protocol.WEBDAVS, dav.protocol)
        assertEquals(8443, dav.port)
        assertEquals("/dav", dav.basePath)

        val kept = ServerAddress.normalize(ServerConfig(Protocol.FTP, host = "ftp.example.com", port = 2121, basePath = "photos/"))
        assertEquals(2121, kept.port)
        assertEquals("photos", kept.basePath)
    }

    @Test
    fun identityIgnoresTransportDetails() {
        val ftp = ServerConfig(Protocol.FTP, host = "NAS.local", username = "me", basePath = "/photos/")
        val ftps = ftp.copy(protocol = Protocol.FTPS, host = "nas.local", port = 990, basePath = "/photos")
        assertEquals(ftp.identity(), ftps.identity())
        assertNotEquals(ftp.identity(), ftp.copy(basePath = "/other").identity())
        assertNotEquals(ftp.identity(), ftp.copy(protocol = Protocol.SFTP).identity())
    }

    @Test
    fun toStringHidesPassword() {
        val config = ServerConfig(Protocol.SFTP, host = "nas", username = "me", password = "hunter2")
        assert(!config.toString().contains("hunter2"))
    }
}
