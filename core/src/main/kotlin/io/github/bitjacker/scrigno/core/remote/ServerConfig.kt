package io.github.bitjacker.scrigno.core.remote

import io.github.bitjacker.scrigno.core.backup.RemotePaths
import java.util.Locale

/**
 * Everything needed to reach the user's own server.
 *
 * @property host IP address or FQDN, without scheme or path.
 * @property basePath folder that holds Scrigno's files. Absolute (`/srv/photos`) or relative to the
 * login folder for FTP/SFTP, relative to the share for SMB, the URL path for WebDAV.
 * @property share SMB only: name of the shared folder.
 * @property domain SMB only: optional domain or workgroup.
 * @property trustedFingerprint identity of the server pinned on first use: SSH host key or TLS
 * certificate fingerprint. Empty until the first successful connection.
 * @property allowSelfSigned TLS only: accept a certificate that is not signed by a known authority,
 * as long as its fingerprint matches [trustedFingerprint].
 */
data class ServerConfig(
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val basePath: String = "",
    val share: String = "",
    val domain: String = "",
    val trustedFingerprint: String = "",
    val allowSelfSigned: Boolean = false,
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port in 1..65535 && (protocol != Protocol.SMB || share.isNotBlank())

    /**
     * Stable identifier of the place where backups are stored. Switching between FTP and FTPS, or
     * changing the port, still points to the same files, so neither is part of the identity.
     */
    fun identity(): String = buildString {
        append(protocol.family).append("://")
        if (username.isNotEmpty()) append(username).append('@')
        append(host.trim().lowercase(Locale.ROOT))
        if (protocol == Protocol.SMB) append('/').append(share.trim().lowercase(Locale.ROOT))
        append('/').append(RemotePaths.normalize(basePath).trimStart('/'))
    }

    /** Never print the password, not even in logs. */
    override fun toString(): String =
        "ServerConfig(protocol=$protocol, host=$host, port=$port, username=$username, " +
            "basePath=$basePath, share=$share, domain=$domain, allowSelfSigned=$allowSelfSigned)"
}
