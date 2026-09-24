package io.github.bitjacker.scrigno.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** What the app knows about a file that is on the server. */
data class BackupRecord(
    val id: Long,
    /** [io.github.bitjacker.scrigno.core.remote.ServerConfig.identity] of the server holding it. */
    val server: String,
    /** MediaStore id of the same photo on the phone, null once it has been removed from the phone. */
    val mediaId: Long?,
    val name: String,
    val mime: String?,
    val size: Long,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** Path relative to the server base folder. */
    val remotePath: String,
    val uploadedAt: Long,
    val onDevice: Boolean,
    /** The user brought it back to the phone on purpose: never suggest removing it again. */
    val keepOnDevice: Boolean,
    /** Small preview kept on the phone for photos that live only on the server. */
    val thumbPath: String?,
) {
    val isVideo: Boolean get() = mime?.startsWith("video/") == true
}

/** Data needed to create a record after an upload. */
data class NewBackup(
    val mediaId: Long?,
    val name: String,
    val mime: String?,
    val size: Long,
    val dateTaken: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val remotePath: String,
    val onDevice: Boolean,
)

/**
 * Local index of what has been backed up where. It never leaves the phone; if it is lost (app
 * reinstalled) it can be rebuilt from the server.
 */
class BackupDatabase(context: Context) : SQLiteOpenHelper(context, "scrigno.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE backups (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                server TEXT NOT NULL,
                media_id INTEGER,
                name TEXT NOT NULL,
                mime TEXT,
                size INTEGER NOT NULL,
                date_taken INTEGER NOT NULL,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0,
                remote_path TEXT NOT NULL,
                uploaded_at INTEGER NOT NULL,
                on_device INTEGER NOT NULL DEFAULT 1,
                keep_on_device INTEGER NOT NULL DEFAULT 0,
                thumb TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX backups_media ON backups(server, media_id)")
        db.execSQL("CREATE UNIQUE INDEX backups_remote ON backups(server, remote_path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first one.
    }

    private fun values(server: String, item: NewBackup, now: Long) = ContentValues().apply {
        put("server", server)
        if (item.mediaId != null) put("media_id", item.mediaId) else putNull("media_id")
        put("name", item.name)
        put("mime", item.mime)
        put("size", item.size)
        put("date_taken", item.dateTaken)
        put("width", item.width)
        put("height", item.height)
        put("duration", item.durationMs)
        put("remote_path", item.remotePath)
        put("uploaded_at", now)
        put("on_device", if (item.onDevice) 1 else 0)
    }

    /** Records a file that has just been uploaded (or found already on the server). */
    fun insertBackup(server: String, item: NewBackup, now: Long = System.currentTimeMillis()): Long =
        writableDatabase.insertWithOnConflict("backups", null, values(server, item, now), SQLiteDatabase.CONFLICT_REPLACE)

    /** Records a file found on the server while rebuilding the index. Returns false if already known. */
    fun insertIfMissing(server: String, item: NewBackup, now: Long = System.currentTimeMillis()): Boolean =
        writableDatabase.insertWithOnConflict("backups", null, values(server, item, now), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun all(): List<BackupRecord> = query(null, null)

    fun byId(id: Long): BackupRecord? = query("_id = ?", arrayOf(id.toString())).firstOrNull()

    /** MediaStore ids already backed up to [server]. */
    fun backedUpMediaIds(server: String): Set<Long> {
        val ids = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT media_id FROM backups WHERE server = ? AND media_id IS NOT NULL",
            arrayOf(server),
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        return ids
    }

    fun onDeviceRecords(server: String): List<BackupRecord> =
        query("server = ? AND on_device = 1 AND media_id IS NOT NULL", arrayOf(server))

    /** The photos are no longer on the phone: they now live only on the server. */
    fun markRemovedFromDevice(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("on_device", 0)
                putNull("media_id")
            }
            ids.forEach { db.update("backups", values, "_id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Records that point to photos which were deleted from the phone outside of the app. */
    fun markMissingFromDevice(existingMediaIds: Set<Long>): Int {
        val missing = query("on_device = 1 AND media_id IS NOT NULL", null)
            .filter { it.mediaId !in existingMediaIds }
            .map { it.id }
        markRemovedFromDevice(missing)
        return missing.size
    }

    /** A photo downloaded back to the phone. */
    fun linkToDevice(record: BackupRecord, mediaId: Long) {
        val id = record.id
        val db = writableDatabase
        // Another record of the same server could still claim this MediaStore id (ids are not
        // reused by Android, but the unique index must never be violated).
        db.delete(
            "backups",
            "server = ? AND media_id = ? AND _id != ?",
            arrayOf(record.server, mediaId.toString(), id.toString()),
        )
        db.update(
            "backups",
            ContentValues().apply {
                put("media_id", mediaId)
                put("on_device", 1)
                put("keep_on_device", 1)
            },
            "_id = ?",
            arrayOf(id.toString()),
        )
    }

    fun setThumb(id: Long, path: String?) {
        writableDatabase.update("backups", ContentValues().apply { put("thumb", path) }, "_id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("backups", "_id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("backups", null, null)
    }

    private fun query(selection: String?, args: Array<String>?): List<BackupRecord> {
        val result = ArrayList<BackupRecord>()
        readableDatabase.query("backups", null, selection, args, null, null, "date_taken DESC").use { cursor ->
            val columns = Columns(cursor)
            while (cursor.moveToNext()) result += columns.read(cursor)
        }
        return result
    }

    private class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow("_id")
        private val server = cursor.getColumnIndexOrThrow("server")
        private val mediaId = cursor.getColumnIndexOrThrow("media_id")
        private val name = cursor.getColumnIndexOrThrow("name")
        private val mime = cursor.getColumnIndexOrThrow("mime")
        private val size = cursor.getColumnIndexOrThrow("size")
        private val dateTaken = cursor.getColumnIndexOrThrow("date_taken")
        private val width = cursor.getColumnIndexOrThrow("width")
        private val height = cursor.getColumnIndexOrThrow("height")
        private val duration = cursor.getColumnIndexOrThrow("duration")
        private val remotePath = cursor.getColumnIndexOrThrow("remote_path")
        private val uploadedAt = cursor.getColumnIndexOrThrow("uploaded_at")
        private val onDevice = cursor.getColumnIndexOrThrow("on_device")
        private val keep = cursor.getColumnIndexOrThrow("keep_on_device")
        private val thumb = cursor.getColumnIndexOrThrow("thumb")

        fun read(c: Cursor) = BackupRecord(
            id = c.getLong(id),
            server = c.getString(server),
            mediaId = if (c.isNull(mediaId)) null else c.getLong(mediaId),
            name = c.getString(name),
            mime = c.getString(mime),
            size = c.getLong(size),
            dateTaken = c.getLong(dateTaken),
            width = c.getInt(width),
            height = c.getInt(height),
            durationMs = c.getLong(duration),
            remotePath = c.getString(remotePath),
            uploadedAt = c.getLong(uploadedAt),
            onDevice = c.getInt(onDevice) == 1,
            keepOnDevice = c.getInt(keep) == 1,
            thumbPath = c.getString(thumb),
        )
    }
}
