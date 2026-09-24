package io.github.bitjacker.scrigno.core.remote

/**
 * Transfer protocols supported by Scrigno.
 *
 * @property defaultPort port used when the user does not type one.
 * @property encrypted whether credentials and files travel encrypted on the network.
 * @property usesTls whether the connection is protected by TLS (so a certificate can be pinned).
 */
enum class Protocol(val defaultPort: Int, val encrypted: Boolean, val usesTls: Boolean) {
    SFTP(22, encrypted = true, usesTls = false),
    FTPS(21, encrypted = true, usesTls = true),
    FTP(21, encrypted = false, usesTls = false),
    SMB(445, encrypted = false, usesTls = false),
    WEBDAVS(443, encrypted = true, usesTls = true),
    WEBDAV(80, encrypted = false, usesTls = false);

    /** Protocols that reach the same files on the same server share a family. */
    internal val family: String
        get() = when (this) {
            FTP, FTPS -> "ftp"
            WEBDAV, WEBDAVS -> "webdav"
            SFTP -> "sftp"
            SMB -> "smb"
        }

    companion object {
        fun fromName(name: String?): Protocol? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
