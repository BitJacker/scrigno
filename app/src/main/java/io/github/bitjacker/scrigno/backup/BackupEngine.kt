package io.github.bitjacker.scrigno.backup

import io.github.bitjacker.scrigno.AppContainer
import io.github.bitjacker.scrigno.core.backup.BackupPlanner
import io.github.bitjacker.scrigno.core.backup.MediaRef
import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.backup.Uploader
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.LocalReadException
import io.github.bitjacker.scrigno.core.remote.RemoteStorageFactory
import io.github.bitjacker.scrigno.core.remote.ServerIdentityChangedException
import io.github.bitjacker.scrigno.core.remote.UntrustedCertificateException
import io.github.bitjacker.scrigno.data.db.NewBackup
import io.github.bitjacker.scrigno.data.media.MediaAccess
import io.github.bitjacker.scrigno.data.media.MediaPermissions
import io.github.bitjacker.scrigno.data.settings.LastBackup
import io.github.bitjacker.scrigno.util.ErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Uploads the photos and videos that are not on the server yet. Every file is verified before it
 * is marked as backed up; an interrupted run simply continues where it stopped next time.
 */
class BackupEngine(private val c: AppContainer) {

    data class Outcome(
        val uploaded: Int = 0,
        val alreadyThere: Int = 0,
        val failed: Int = 0,
        val bytes: Long = 0,
        val error: Throwable? = null,
        /** The error will not go away by retrying (wrong password, changed server identity...). */
        val permanent: Boolean = false,
        val skipped: Boolean = false,
    )

    suspend fun run(isStopped: () -> Boolean): Outcome = withContext(Dispatchers.IO) {
        if (!LOCK.tryLock()) return@withContext Outcome(skipped = true) // another backup is running
        c.backupStatus.clearStop()
        try {
            val outcome = doRun(isStopped)
            if (!outcome.skipped) {
                c.settings.recordBackup(
                    LastBackup(
                        finishedAt = System.currentTimeMillis(),
                        uploaded = outcome.uploaded,
                        failed = outcome.failed,
                        error = outcome.error?.let { ErrorMessages.describe(c.context, it) },
                    ),
                )
            }
            outcome
        } finally {
            c.backupStatus.update(BackupState.Idle)
            LOCK.unlock()
            runCatching { c.library.refresh() }
        }
    }

    private fun doRun(isStopped: () -> Boolean): Outcome {
        val settings = c.settings.settings.value
        val server = c.settings.server.value
            ?: return Outcome(skipped = true)
        val access = MediaPermissions.access(c.context)
        if (access == MediaAccess.NONE) {
            return Outcome(error = SecurityException("No access to photos"), permanent = true)
        }

        val device = c.mediaStore.query(includeVideos = true)
        if (access == MediaAccess.FULL) {
            // Photos deleted from the phone outside of Scrigno now live only on the server.
            c.database.markMissingFromDevice(device.mapTo(HashSet()) { it.id })
        }

        val identity = server.identity()
        val refs = device.map { MediaRef(it.id, it.size, it.dateTaken, it.bucketId, it.isVideo) }
        val pendingIds = BackupPlanner.pending(
            media = refs,
            alreadyBackedUp = c.database.backedUpMediaIds(identity),
            selectedBuckets = if (settings.allFolders) null else settings.selectedFolders,
            includeVideos = settings.includeVideos,
        ).map { it.id }
        val byId = device.associateBy { it.id }
        val pending = pendingIds.mapNotNull { byId[it] }
        if (pending.isEmpty()) return Outcome()

        val bytesTotal = pending.sumOf { it.size }
        var bytesDone = 0L
        var lastUpdate = 0L
        fun publish(index: Int, name: String?, extra: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUpdate < 300) return
            lastUpdate = now
            c.backupStatus.update(BackupState.Running(index, pending.size, name, bytesDone + extra, bytesTotal))
        }
        publish(0, null, 0, force = true)

        var uploaded = 0
        var alreadyThere = 0
        var failed = 0
        var consecutiveFailures = 0
        var lastError: Throwable? = null
        val storage = RemoteStorageFactory.create(server)
        try {
            storage.connect()
            c.settings.pinFingerprintIfNew(server, storage.serverFingerprint)
            val createdDirs = HashSet<String>()
            for ((index, media) in pending.withIndex()) {
                if (isStopped() || c.backupStatus.stopRequested) break
                publish(index, media.name, 0, force = true)
                val dir = RemotePaths.join(c.settings.deviceFolder, RemotePaths.monthFolder(media.dateTaken))
                try {
                    if (dir !in createdDirs) {
                        storage.mkdirs(dir)
                        createdDirs += dir
                    }
                    val result = Uploader.upload(
                        storage = storage,
                        dir = dir,
                        fileName = media.name,
                        expectedSize = media.size,
                        modifiedMillis = media.dateTaken,
                        open = { c.mediaStore.openOriginal(media) },
                    ) { sent -> publish(index, media.name, sent) }
                    c.database.insertBackup(
                        identity,
                        NewBackup(
                            mediaId = media.id,
                            name = media.name,
                            mime = media.mime,
                            size = if (result.alreadyPresent) media.size else result.bytes,
                            dateTaken = media.dateTaken,
                            width = media.width,
                            height = media.height,
                            durationMs = media.durationMs,
                            remotePath = result.remotePath,
                            onDevice = true,
                        ),
                    )
                    if (result.alreadyPresent) alreadyThere++ else uploaded++
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LocalReadException) {
                    // The file cannot be read on the phone (deleted meanwhile, broken...): skip it.
                    failed++
                    lastError = e
                } catch (e: Exception) {
                    failed++
                    lastError = e
                    consecutiveFailures++
                    if (isPermanent(e) || consecutiveFailures >= 3) break
                    // The connection may be broken after a failed transfer: open a new one.
                    createdDirs.clear()
                    try {
                        storage.connect()
                    } catch (reconnect: Exception) {
                        lastError = reconnect
                        break
                    }
                }
                bytesDone += media.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome(uploaded, alreadyThere, failed, bytesDone, e, isPermanent(e))
        } finally {
            runCatching { storage.close() }
        }
        val error = if (failed > 0) lastError else null
        return Outcome(uploaded, alreadyThere, failed, bytesDone, error, error != null && isPermanent(error))
    }

    private fun isPermanent(e: Throwable) = e is AuthenticationException ||
        e is ServerIdentityChangedException ||
        e is UntrustedCertificateException ||
        e is SecurityException

    private companion object {
        val LOCK = Mutex()
    }
}
