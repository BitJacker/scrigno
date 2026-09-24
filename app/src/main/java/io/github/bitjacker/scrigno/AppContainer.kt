package io.github.bitjacker.scrigno

import android.content.Context
import io.github.bitjacker.scrigno.backup.BackupScheduler
import io.github.bitjacker.scrigno.backup.BackupStatus
import io.github.bitjacker.scrigno.backup.FreeSpaceManager
import io.github.bitjacker.scrigno.data.db.BackupDatabase
import io.github.bitjacker.scrigno.data.library.LibraryRepository
import io.github.bitjacker.scrigno.data.media.MediaStoreSource
import io.github.bitjacker.scrigno.data.media.ThumbnailStore
import io.github.bitjacker.scrigno.data.remote.OriginalsRepository
import io.github.bitjacker.scrigno.data.remote.RemoteSessions
import io.github.bitjacker.scrigno.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand made dependency injection: one instance of each service for the whole app. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val database = BackupDatabase(context)
    val mediaStore = MediaStoreSource(context)
    val thumbnails = ThumbnailStore(context)
    val sessions = RemoteSessions(settings, appScope)
    val library = LibraryRepository(context, mediaStore, database, settings)
    val originals = OriginalsRepository(context, sessions, database, thumbnails, mediaStore, library, settings)
    val backupStatus = BackupStatus()
    val scheduler = BackupScheduler(context)
    val freeSpace = FreeSpaceManager(context, database, mediaStore, thumbnails, sessions, settings, library)
}
