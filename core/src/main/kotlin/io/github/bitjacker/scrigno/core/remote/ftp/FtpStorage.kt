package io.github.bitjacker.scrigno.core.remote.ftp

import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.io.Streams
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.BaseRemoteStorage
import io.github.bitjacker.scrigno.core.remote.PinningTrustManager
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.RemoteFile
import io.github.bitjacker.scrigno.core.remote.RemoteNotFoundException
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.core.remote.Timeouts
import okhttp3.internal.tls.OkHostnameVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** FTP and FTPS (explicit TLS, "FTPES"), based on Apache Commons Net. */
class FtpStorage(
    override val config: ServerConfig,
    private val timeouts: Timeouts = Timeouts(),
) : BaseRemoteStorage() {

    private var client: FTPClient? = null
    private var trustManager: PinningTrustManager? = null
    private var useMlst = false

    override val serverFingerprint: String?
        get() = trustManager?.observedFingerprint

    override val isConnected: Boolean
        get() = client?.isConnected == true

    override fun connect() {
        close()
        val ftp: FTPClient = if (config.protocol == Protocol.FTPS) {
            val tm = PinningTrustManager(config.trustedFingerprint, config.allowSelfSigned)
            trustManager = tm
            FTPSClient("TLS", false).apply {
                setTrustManager(tm)
                hostnameVerifier = tm.hostnameVerifier(OkHostnameVerifier)
            }
        } else {
            trustManager = null
            FTPClient()
        }
        ftp.controlEncoding = "UTF-8"
        ftp.connectTimeout = timeouts.connectMillis
        ftp.defaultTimeout = timeouts.readMillis
        ftp.setDataTimeout(Duration.ofMillis(timeouts.readMillis.toLong()))
        try {
            ftp.connect(config.host, config.port)
            ftp.soTimeout = timeouts.readMillis
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                throw RemoteException("The FTP server refused the connection: ${ftp.replyString.trim()}")
            }
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            val anonymous = config.username.isEmpty()
            val loggedIn = ftp.login(
                if (anonymous) "anonymous" else config.username,
                if (anonymous) "anonymous@" else config.password,
            )
            if (!loggedIn) throw AuthenticationException("Login refused: ${ftp.replyString.trim()}")
            ftp.enterLocalPassiveMode()
            // EPSV answers with a port only: the data connection goes to the same address as the
            // control one, so it works behind NAT (home routers, Android emulator). Falls back to PASV.
            ftp.setUseEPSVwithIPv4(true)
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw RemoteException("Binary mode refused: ${ftp.replyString.trim()}")
            }
            useMlst = runCatching { ftp.hasFeature("MLST") }.getOrDefault(false)
            runCatching { if (ftp.hasFeature("UTF8")) ftp.sendCommand("OPTS UTF8 ON") }
        } catch (e: Exception) {
            runCatching { ftp.disconnect() }
            throw translate(e)
        }
        client = ftp
    }

    private fun translate(e: Exception): IOException {
        if (e is SSLException) trustManager?.explain(e)?.let { return it }
        return e as? IOException ?: RemoteException(e.message ?: e.toString(), e)
    }

    private fun ftp(): FTPClient = client?.takeIf { it.isConnected } ?: throw RemoteException("Not connected")

    private fun reply(): String = client?.replyString?.trim().orEmpty()

    override fun statResolved(resolved: String): Attrs? {
        val ftp = ftp()
        if (RemotePaths.segments(resolved).isEmpty()) return Attrs(isDirectory = true, size = 0, modifiedMillis = null)
        if (useMlst) {
            val file = ftp.mlistFile(resolved)
            if (file != null) return file.toAttrs()
            val code = ftp.replyCode
            if (code !in 500..504) return null // 550: does not exist
            useMlst = false // the server announced MLST but does not implement it: fall back to LIST
        }
        val parent = RemotePaths.parent(resolved)
        val name = RemotePaths.name(resolved)
        val entries = if (parent.isEmpty()) ftp.listFiles() else ftp.listFiles(parent)
        return entries.orEmpty()
            .firstOrNull { it != null && RemotePaths.name(it.name) == name }
            ?.toAttrs()
    }

    private fun FTPFile.toAttrs() = Attrs(isDirectory, size.coerceAtLeast(0), timestamp?.timeInMillis)

    override fun mkdirResolved(resolved: String) {
        if (!ftp().makeDirectory(resolved)) throw RemoteException("Cannot create folder $resolved: ${reply()}")
    }

    override fun list(path: String): List<RemoteFile> {
        val ftp = ftp()
        val full = resolve(path)
        val entries = when {
            useMlst -> if (full.isEmpty()) ftp.mlistDir() else ftp.mlistDir(full)
            full.isEmpty() -> ftp.listFiles()
            else -> ftp.listFiles(full)
        }
        return entries.orEmpty()
            .filterNotNull()
            .map { it to RemotePaths.name(it.name) }
            .filter { (_, name) -> name.isNotEmpty() && name != "." && name != ".." }
            .map { (file, name) ->
                RemoteFile(
                    name = name,
                    path = childOf(path, name),
                    isDirectory = file.isDirectory,
                    size = file.size.coerceAtLeast(0),
                    modifiedMillis = file.timestamp?.timeInMillis,
                )
            }
    }

    override fun upload(path: String, input: InputStream, length: Long, modifiedMillis: Long?, onProgress: (Long) -> Unit) {
        val ftp = ftp()
        val out = ftp.storeFileStream(resolve(path)) ?: throw RemoteException("Upload refused: ${reply()}")
        out.use { Streams.copy(input, it, onProgress) }
        if (!ftp.completePendingCommand()) throw RemoteException("Upload failed: ${reply()}")
    }

    override fun download(path: String, output: OutputStream, onProgress: (Long) -> Unit) {
        val ftp = ftp()
        val input = ftp.retrieveFileStream(resolve(path))
        if (input == null) {
            if (ftp.replyCode == 550) throw RemoteNotFoundException(path)
            throw RemoteException("Download refused: ${reply()}")
        }
        input.use { Streams.copy(it, output, onProgress) }
        if (!ftp.completePendingCommand()) throw RemoteException("Download failed: ${reply()}")
    }

    override fun rename(from: String, to: String) {
        if (!ftp().rename(resolve(from), resolve(to))) throw RemoteException("Cannot rename $from: ${reply()}")
    }

    override fun delete(path: String) {
        if (!ftp().deleteFile(resolve(path))) throw RemoteException("Cannot delete $path: ${reply()}")
    }

    override fun setModified(path: String, epochMillis: Long) {
        val time = MFMT_FORMAT.format(Instant.ofEpochMilli(epochMillis))
        ftp().setModificationTime(resolve(path), time)
    }

    override fun close() {
        val ftp = client ?: return
        client = null
        runCatching { if (ftp.isConnected) ftp.logout() }
        runCatching { ftp.disconnect() }
    }

    private companion object {
        val MFMT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    }
}
