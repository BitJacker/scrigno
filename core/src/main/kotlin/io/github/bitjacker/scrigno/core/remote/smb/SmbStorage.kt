package io.github.bitjacker.scrigno.core.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.io.Streams
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.BaseRemoteStorage
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.RemoteFile
import io.github.bitjacker.scrigno.core.remote.RemoteNotFoundException
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.core.remote.Timeouts
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB 2/3 (Windows shares, Samba, most NAS), based on smbj. */
class SmbStorage(
    override val config: ServerConfig,
    private val timeouts: Timeouts = Timeouts(),
) : BaseRemoteStorage() {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    override val serverFingerprint: String? get() = null

    override val isConnected: Boolean
        get() = connection?.isConnected == true && share?.isConnected == true

    override fun connect() {
        close()
        val smbConfig = SmbConfig.builder()
            .withTimeout(timeouts.readMillis.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(timeouts.readMillis.toLong(), TimeUnit.MILLISECONDS)
            // Android has no MD4 in its crypto providers: NTLM needs the Bouncy Castle implementation.
            .withSecurityProvider(BCSecurityProvider())
            .build()
        val smb = SMBClient(smbConfig)
        try {
            val conn = smb.connect(config.host, config.port)
            val auth = if (config.username.isEmpty()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifEmpty { null })
            }
            val sess = try {
                conn.authenticate(auth)
            } catch (e: SMBApiException) {
                throw AuthenticationException("Login refused by the SMB server (${e.status})", e)
            }
            val disk = try {
                sess.connectShare(config.share.trim()) as? DiskShare
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_ACCESS_DENIED) throw AuthenticationException("Access to the share denied", e)
                throw RemoteException("Cannot open the share \"${config.share}\" (${e.status})", e)
            } ?: throw RemoteException("\"${config.share}\" is not a shared folder")
            client = smb
            connection = conn
            session = sess
            share = disk
        } catch (e: Exception) {
            runCatching { smb.close() }
            throw e as? IOException ?: RemoteException(e.message ?: e.toString(), e)
        }
    }

    private fun disk(): DiskShare = share?.takeIf { it.isConnected } ?: throw RemoteException("Not connected")

    /** smbj wants paths relative to the share, separated by backslashes, without a leading one. */
    private fun smbPath(resolved: String): String = RemotePaths.segments(resolved).joinToString("\\")

    private fun SMBApiException.isNotFound() = status in NOT_FOUND

    private fun SMBApiException.toRemote(path: String): RemoteException = when {
        isNotFound() -> RemoteNotFoundException(path, this)
        status == NtStatus.STATUS_ACCESS_DENIED -> AuthenticationException("Permission denied: $path", this)
        else -> RemoteException("SMB error on $path: $status", this)
    }

    /** Runs an smbj call, turning its (unchecked) exceptions into [RemoteException]s. */
    private inline fun <T> smb(path: String, block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw e.toRemote(path)
    } catch (e: SMBRuntimeException) {
        throw RemoteException("SMB error on $path: ${e.message}", e)
    }

    private fun open(resolved: String, access: Set<AccessMask>, disposition: SMB2CreateDisposition) = disk().openFile(
        smbPath(resolved),
        access,
        EnumSet.noneOf(FileAttributes::class.java),
        SMB2ShareAccess.ALL,
        disposition,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    override fun statResolved(resolved: String): Attrs? {
        val path = smbPath(resolved)
        if (path.isEmpty()) return Attrs(isDirectory = true, size = 0, modifiedMillis = null)
        return try {
            smb(resolved) {
                val info = disk().getFileInformation(path)
                Attrs(
                    isDirectory = info.standardInformation.isDirectory,
                    size = info.standardInformation.endOfFile,
                    modifiedMillis = info.basicInformation.lastWriteTime.toEpochMillis(),
                )
            }
        } catch (e: RemoteNotFoundException) {
            null
        }
    }

    override fun mkdirResolved(resolved: String) = smb(resolved) {
        disk().mkdir(smbPath(resolved))
    }

    override fun list(path: String): List<RemoteFile> {
        val full = resolve(path)
        val entries = smb(full) { disk().list(smbPath(full)) }
        return entries
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                RemoteFile(
                    name = info.fileName,
                    path = childOf(path, info.fileName),
                    isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = info.endOfFile,
                    modifiedMillis = info.lastWriteTime.toEpochMillis(),
                )
            }
    }

    override fun upload(path: String, input: InputStream, length: Long, modifiedMillis: Long?, onProgress: (Long) -> Unit) {
        val full = resolve(path)
        smb(full) {
            open(full, EnumSet.of(AccessMask.GENERIC_WRITE), SMB2CreateDisposition.FILE_OVERWRITE_IF).use { file ->
                file.outputStream.use { out -> Streams.copy(input, out, onProgress) }
            }
        }
    }

    override fun download(path: String, output: OutputStream, onProgress: (Long) -> Unit) {
        val full = resolve(path)
        smb(full) {
            open(full, EnumSet.of(AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN).use { file ->
                // Read exactly the known size instead of reading until the server says "end of
                // file": some NAS/embedded servers answer that last request in a malformed way.
                val size = file.fileInformation.standardInformation.endOfFile
                val buffer = ByteArray(Streams.BUFFER_SIZE)
                var offset = 0L
                while (offset < size) {
                    val wanted = minOf(buffer.size.toLong(), size - offset).toInt()
                    val read = file.read(buffer, offset, 0, wanted)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    offset += read
                    onProgress(offset)
                }
                output.flush()
                if (offset < size) throw RemoteException("Download of $path interrupted at $offset of $size bytes")
            }
        }
    }

    override fun rename(from: String, to: String) {
        val source = resolve(from)
        smb(source) {
            open(source, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN).use { file ->
                file.rename(smbPath(resolve(to)), false)
            }
        }
    }

    override fun delete(path: String) {
        val full = resolve(path)
        smb(full) { disk().rm(smbPath(full)) }
    }

    override fun setModified(path: String, epochMillis: Long) {
        val full = resolve(path)
        smb(full) {
            open(full, EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN).use { file ->
                val time = FileTime.ofEpochMillis(epochMillis)
                file.setFileInformation(
                    FileBasicInformation(
                        FileBasicInformation.DONT_SET,
                        FileBasicInformation.DONT_SET,
                        time,
                        FileBasicInformation.DONT_SET,
                        0L,
                    ),
                )
            }
        }
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    private companion object {
        val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
        )
    }
}
