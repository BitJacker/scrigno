package io.github.bitjacker.scrigno.core.backup

import io.github.bitjacker.scrigno.core.io.CountingInputStream
import io.github.bitjacker.scrigno.core.remote.LocalReadException
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.RemoteStorage
import java.io.IOException
import java.io.InputStream

/**
 * @property remotePath where the file is on the server, relative to the base folder.
 * @property alreadyPresent the same file (same name and size) was already on the server, so
 * nothing was transferred. Happens after reinstalling the app.
 */
data class UploadResult(val remotePath: String, val alreadyPresent: Boolean, val bytes: Long)

/**
 * Uploads a file safely:
 * 1. picks a free name (never overwrites a different file with the same name),
 * 2. uploads to a temporary name, so an interrupted transfer never looks like a complete file,
 * 3. renames it and checks that the size on the server matches what was sent.
 *
 * Only a file that passed step 3 may later be removed from the phone.
 */
object Uploader {
    private const val MAX_NAME_ATTEMPTS = 500

    @Throws(IOException::class)
    fun upload(
        storage: RemoteStorage,
        dir: String,
        fileName: String,
        expectedSize: Long,
        modifiedMillis: Long?,
        open: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): UploadResult {
        val safeName = RemotePaths.sanitizeFileName(fileName)
        val (target, alreadyPresent) = pickTarget(storage, dir, safeName, expectedSize)
        if (alreadyPresent) return UploadResult(target, alreadyPresent = true, bytes = 0)

        val temp = RemotePaths.join(dir, RemotePaths.tempName(RemotePaths.name(target)))
        val input = try {
            open()
        } catch (e: Exception) {
            throw LocalReadException(fileName, e)
        }
        val sent = CountingInputStream(input).use { counting ->
            storage.upload(temp, counting, expectedSize, modifiedMillis, onProgress)
            counting.count
        }
        storage.rename(temp, target)

        val onServer = storage.stat(target)
            ?: throw RemoteException("The uploaded file is not on the server: $target")
        if (onServer.size != sent) {
            runCatching { storage.delete(target) }
            throw RemoteException("Size mismatch for $target: sent $sent bytes, the server has ${onServer.size}")
        }
        if (modifiedMillis != null && modifiedMillis > 0) {
            runCatching { storage.setModified(target, modifiedMillis) }
        }
        return UploadResult(target, alreadyPresent = false, bytes = sent)
    }

    /** Returns the path to write to and whether an identical file is already there. */
    private fun pickTarget(storage: RemoteStorage, dir: String, name: String, expectedSize: Long): Pair<String, Boolean> {
        for (attempt in 0 until MAX_NAME_ATTEMPTS) {
            val path = RemotePaths.join(dir, RemotePaths.withCollisionSuffix(name, attempt))
            val existing = storage.stat(path) ?: return path to false
            if (!existing.isDirectory && expectedSize >= 0 && existing.size == expectedSize) return path to true
        }
        throw RemoteException("Too many files called $name in $dir")
    }
}
