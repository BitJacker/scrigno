package io.github.bitjacker.scrigno.core.remote

import io.github.bitjacker.scrigno.core.remote.ftp.FtpStorage
import io.github.bitjacker.scrigno.core.remote.sftp.SftpStorage
import io.github.bitjacker.scrigno.core.remote.smb.SmbStorage
import io.github.bitjacker.scrigno.core.remote.webdav.WebDavStorage

object RemoteStorageFactory {
    fun create(config: ServerConfig, timeouts: Timeouts = Timeouts()): RemoteStorage = when (config.protocol) {
        Protocol.FTP, Protocol.FTPS -> FtpStorage(config, timeouts)
        Protocol.SFTP -> SftpStorage(config, timeouts)
        Protocol.SMB -> SmbStorage(config, timeouts)
        Protocol.WEBDAV, Protocol.WEBDAVS -> WebDavStorage(config, timeouts)
    }
}
