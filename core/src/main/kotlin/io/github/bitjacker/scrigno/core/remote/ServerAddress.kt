package io.github.bitjacker.scrigno.core.remote

import io.github.bitjacker.scrigno.core.backup.RemotePaths
import java.util.Locale

/**
 * Understands what people paste in the "server" field: `192.168.1.10`, `nas.local:2222`,
 * `sftp://nas.home.arpa/photos`, `https://cloud.example.com/remote.php/dav/files/me`,
 * `smb://nas/Photos/Phone`, `[fd00::1]:22`...
 */
object ServerAddress {

    data class Parsed(val host: String, val port: Int?, val path: String?, val protocol: Protocol?)

    fun parse(raw: String): Parsed {
        var rest = raw.trim()
        var protocol: Protocol? = null
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd > 0) {
            protocol = when (rest.substring(0, schemeEnd).lowercase(Locale.ROOT)) {
                "ftp" -> Protocol.FTP
                "ftps", "ftpes" -> Protocol.FTPS
                "sftp", "ssh" -> Protocol.SFTP
                "smb", "cifs" -> Protocol.SMB
                "http", "dav", "webdav" -> Protocol.WEBDAV
                "https", "davs", "webdavs" -> Protocol.WEBDAVS
                else -> null
            }
            rest = rest.substring(schemeEnd + 3)
        }
        rest = rest.replace('\\', '/').trimStart('/')

        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) RemotePaths.normalize(rest.substring(slash)).takeIf { it != "/" && it.isNotEmpty() } else null
        val hostPort = authority.substringAfterLast('@')

        var host: String
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            host = if (close > 0) hostPort.substring(1, close) else hostPort.removePrefix("[")
            if (close > 0 && hostPort.length > close + 1 && hostPort[close + 1] == ':') {
                port = hostPort.substring(close + 2).toIntOrNull()
            }
        } else if (hostPort.count { it == ':' } == 1) {
            host = hostPort.substringBefore(':')
            port = hostPort.substringAfter(':').toIntOrNull()
        } else {
            host = hostPort // a plain name, IPv4, or an IPv6 address without brackets
        }
        host = host.trim().trimEnd('.')
        return Parsed(host = host, port = port?.takeIf { it in 1..65535 }, path = path, protocol = protocol)
    }

    /**
     * Cleans a configuration typed by the user: a URL in the host field is split into its parts,
     * and for SMB the first folder of the path becomes the share when none was given.
     */
    fun normalize(config: ServerConfig): ServerConfig {
        val parsed = parse(config.host)
        val protocol = parsed.protocol ?: config.protocol
        val portFromHost = parsed.port
        val port = when {
            portFromHost != null -> portFromHost
            protocol != config.protocol && config.port == config.protocol.defaultPort -> protocol.defaultPort
            else -> config.port
        }
        var share = config.share.trim().trim('/', '\\')
        var basePath = RemotePaths.normalize(config.basePath)
        var pathFromHost = parsed.path
        if (protocol == Protocol.SMB && pathFromHost != null && share.isEmpty()) {
            val segments = RemotePaths.segments(pathFromHost)
            share = segments.first()
            pathFromHost = segments.drop(1).joinToString("/")
        }
        if (!pathFromHost.isNullOrEmpty() && basePath.isEmpty()) {
            basePath = if (protocol == Protocol.SMB) RemotePaths.normalize(pathFromHost).trimStart('/') else pathFromHost
        }
        if (protocol == Protocol.SMB) basePath = basePath.trimStart('/')
        return config.copy(
            protocol = protocol,
            host = parsed.host,
            port = port,
            username = config.username.trim(),
            basePath = basePath,
            share = share,
            domain = config.domain.trim(),
        )
    }
}
