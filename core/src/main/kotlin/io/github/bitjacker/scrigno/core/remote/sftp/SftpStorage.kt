package io.github.bitjacker.scrigno.core.remote.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.BaseRemoteStorage
import io.github.bitjacker.scrigno.core.remote.Fingerprints
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.RemoteFile
import io.github.bitjacker.scrigno.core.remote.RemoteNotFoundException
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.core.remote.ServerIdentityChangedException
import io.github.bitjacker.scrigno.core.remote.Timeouts
import java.io.InputStream
import java.io.OutputStream

/** SFTP (file transfer over SSH), based on JSch. The server host key is pinned on first use. */
class SftpStorage(
    override val config: ServerConfig,
    private val timeouts: Timeouts = Timeouts(),
) : BaseRemoteStorage() {

    private var session: Session? = null
    private var channel: ChannelSftp? = null

    @Volatile
    private var observedFingerprint: String? = null

    override val serverFingerprint: String?
        get() = observedFingerprint

    override val isConnected: Boolean
        get() = session?.isConnected == true && channel?.isConnected == true

    override fun connect() {
        close()
        val jsch = JSch()
        val hostKeys = TofuHostKeyRepository(config.trustedFingerprint)
        jsch.hostKeyRepository = hostKeys
        val ssh = jsch.getSession(config.username, config.host, config.port)
        ssh.setPassword(config.password)
        ssh.userInfo = PasswordUserInfo(config.password)
        ssh.setConfig("StrictHostKeyChecking", "yes")
        ssh.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // Old NAS firmwares only offer RSA host keys signed with SHA-1: accept them as a last resort.
        ssh.getConfig("server_host_key")?.let { if (!it.contains("ssh-rsa")) ssh.setConfig("server_host_key", "$it,ssh-rsa") }
        ssh.timeout = timeouts.readMillis
        try {
            ssh.connect(timeouts.connectMillis)
        } catch (e: JSchException) {
            observedFingerprint = hostKeys.observedFingerprint
            val changed = hostKeys.mismatch
            if (changed != null) throw ServerIdentityChangedException(config.trustedFingerprint, changed, e)
            val message = e.message.orEmpty()
            if (message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true)) {
                throw AuthenticationException("Login refused by the SSH server", e)
            }
            throw RemoteException(message.ifEmpty { "SSH connection failed" }, e)
        }
        observedFingerprint = hostKeys.observedFingerprint
        try {
            val sftp = ssh.openChannel("sftp") as ChannelSftp
            sftp.connect(timeouts.connectMillis)
            session = ssh
            channel = sftp
        } catch (e: JSchException) {
            ssh.disconnect()
            throw RemoteException("The server does not offer SFTP: ${e.message}", e)
        }
    }

    private fun sftp(): ChannelSftp = channel?.takeIf { it.isConnected } ?: throw RemoteException("Not connected")

    private fun SftpException.toRemote(path: String): RemoteException =
        if (id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            RemoteNotFoundException(path, this)
        } else if (id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            AuthenticationException("Permission denied: $path", this)
        } else {
            RemoteException("SFTP error on $path: ${message ?: id}", this)
        }

    private fun pathOrDot(resolved: String) = resolved.ifEmpty { "." }

    override fun statResolved(resolved: String): Attrs? = try {
        val attrs = sftp().stat(pathOrDot(resolved))
        Attrs(attrs.isDir, attrs.size, attrs.mTime.toLong() * 1000L)
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e.toRemote(resolved)
    }

    override fun mkdirResolved(resolved: String) {
        try {
            sftp().mkdir(resolved)
        } catch (e: SftpException) {
            throw e.toRemote(resolved)
        }
    }

    override fun list(path: String): List<RemoteFile> {
        val full = resolve(path)
        val entries = try {
            sftp().ls(pathOrDot(full))
        } catch (e: SftpException) {
            throw e.toRemote(full)
        }
        return entries
            .filter { it.filename != "." && it.filename != ".." }
            .map { entry ->
                RemoteFile(
                    name = entry.filename,
                    path = childOf(path, entry.filename),
                    isDirectory = entry.attrs.isDir,
                    size = entry.attrs.size,
                    modifiedMillis = entry.attrs.mTime.toLong() * 1000L,
                )
            }
    }

    override fun upload(path: String, input: InputStream, length: Long, modifiedMillis: Long?, onProgress: (Long) -> Unit) {
        val full = resolve(path)
        try {
            sftp().put(input, full, ProgressMonitor(onProgress), ChannelSftp.OVERWRITE)
        } catch (e: SftpException) {
            throw e.toRemote(full)
        }
    }

    override fun download(path: String, output: OutputStream, onProgress: (Long) -> Unit) {
        val full = resolve(path)
        try {
            sftp().get(full, output, ProgressMonitor(onProgress))
        } catch (e: SftpException) {
            throw e.toRemote(full)
        }
    }

    override fun rename(from: String, to: String) {
        try {
            sftp().rename(resolve(from), resolve(to))
        } catch (e: SftpException) {
            throw e.toRemote(from)
        }
    }

    override fun delete(path: String) {
        try {
            sftp().rm(resolve(path))
        } catch (e: SftpException) {
            throw e.toRemote(path)
        }
    }

    override fun setModified(path: String, epochMillis: Long) {
        try {
            sftp().setMtime(resolve(path), (epochMillis / 1000L).toInt())
        } catch (e: SftpException) {
            throw e.toRemote(path)
        }
    }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    private class ProgressMonitor(private val onProgress: (Long) -> Unit) : SftpProgressMonitor {
        private var transferred = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(count: Long): Boolean {
            transferred += count
            onProgress(transferred)
            return true
        }
        override fun end() {}
    }

    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }
}

/**
 * Host key store holding a single pinned fingerprint. The first key seen is accepted (and reported
 * through [observedFingerprint] so the app can save it); a different key later is refused.
 */
internal class TofuHostKeyRepository(private val pinned: String) : HostKeyRepository {
    @Volatile
    var observedFingerprint: String? = null
        private set

    /** Fingerprint of the refused key when it did not match the pinned one. */
    @Volatile
    var mismatch: String? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val fingerprint = Fingerprints.ssh(key)
        observedFingerprint = fingerprint
        return if (pinned.isEmpty() || pinned == fingerprint) {
            HostKeyRepository.OK
        } else {
            mismatch = fingerprint
            HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "scrigno"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
