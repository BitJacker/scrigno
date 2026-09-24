package io.github.bitjacker.scrigno.core.remote

import io.github.bitjacker.scrigno.core.backup.RemotePaths
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file or folder on the server. [path] is relative to [ServerConfig.basePath]. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMillis: Long?,
)

data class Timeouts(val connectMillis: Int = 20_000, val readMillis: Int = 60_000)

/**
 * A connection to the user's server. All paths are relative to [ServerConfig.basePath] and use `/`
 * as separator, whatever the protocol. Implementations are not thread safe: use one instance per
 * thread (or guard it with a lock).
 */
interface RemoteStorage : Closeable {
    val config: ServerConfig

    /** Identity presented by the server on the last [connect] (SSH host key or TLS certificate). */
    val serverFingerprint: String?

    val isConnected: Boolean

    /** Opens the connection and logs in. Calling it again reconnects. */
    @Throws(IOException::class)
    fun connect()

    /** Creates [path] and every missing parent folder. */
    @Throws(IOException::class)
    fun mkdirs(path: String)

    /** Returns the file or folder at [path], or null when it does not exist. */
    @Throws(IOException::class)
    fun stat(path: String): RemoteFile?

    @Throws(IOException::class)
    fun list(path: String): List<RemoteFile>

    /**
     * Writes [input] to [path], replacing an existing file. [length] may be -1 when unknown.
     * [onProgress] receives the number of bytes sent so far.
     */
    @Throws(IOException::class)
    fun upload(
        path: String,
        input: InputStream,
        length: Long,
        modifiedMillis: Long? = null,
        onProgress: (Long) -> Unit = {},
    )

    @Throws(IOException::class)
    fun download(path: String, output: OutputStream, onProgress: (Long) -> Unit = {})

    /** Renames a file. The destination must not exist. */
    @Throws(IOException::class)
    fun rename(from: String, to: String)

    @Throws(IOException::class)
    fun delete(path: String)

    /** Best effort: sets the modification time of a file (so the server shows the capture date). */
    @Throws(IOException::class)
    fun setModified(path: String, epochMillis: Long) {
    }
}

/** Shared logic: path resolution and recursive folder creation. */
abstract class BaseRemoteStorage : RemoteStorage {

    /** Attributes of an entry, as seen by [statResolved]. */
    protected data class Attrs(val isDirectory: Boolean, val size: Long, val modifiedMillis: Long?)

    /** Turns a path relative to the base folder into the path understood by the server. */
    protected fun resolve(path: String): String = RemotePaths.join(config.basePath, path)

    /** Stats a path that has already been [resolve]d. Returns null when it does not exist. */
    protected abstract fun statResolved(resolved: String): Attrs?

    /** Creates a single folder whose parent exists. */
    protected abstract fun mkdirResolved(resolved: String)

    override fun stat(path: String): RemoteFile? {
        val attrs = statResolved(resolve(path)) ?: return null
        return RemoteFile(
            name = RemotePaths.name(path),
            path = RemotePaths.normalize(path),
            isDirectory = attrs.isDirectory,
            size = attrs.size,
            modifiedMillis = attrs.modifiedMillis,
        )
    }

    override fun mkdirs(path: String) {
        val full = resolve(path)
        val segments = RemotePaths.segments(full)
        if (segments.isEmpty()) return
        val absolute = full.startsWith("/")
        fun prefix(count: Int) = (if (absolute) "/" else "") + segments.take(count).joinToString("/")

        // Walk up to the deepest folder that already exists, so that folders above the base path
        // (which the user may not be allowed to touch) are never created or even modified.
        var existing = segments.size
        while (existing > 0 && statResolved(prefix(existing))?.isDirectory != true) existing--
        for (count in existing + 1..segments.size) {
            val dir = prefix(count)
            try {
                mkdirResolved(dir)
            } catch (e: IOException) {
                // Somebody else may have created it in the meantime.
                if (statResolved(dir)?.isDirectory != true) throw e
            }
        }
    }

    protected fun childOf(parent: String, name: String) = RemotePaths.join(parent, name)
}
