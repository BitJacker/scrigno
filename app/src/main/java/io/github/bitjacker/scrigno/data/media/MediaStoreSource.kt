package io.github.bitjacker.scrigno.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.bitjacker.scrigno.core.backup.RemotePaths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A photo or video on the phone, as seen by Android's MediaStore. */
data class DeviceMedia(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: String,
    val bucketName: String,
    val isVideo: Boolean,
)

/** An album / folder of the phone (Camera, WhatsApp Images, Screenshots...). */
data class DeviceFolder(val id: String, val name: String, val count: Int, val cover: DeviceMedia)

/** Reads (and when asked, writes or deletes) photos and videos through MediaStore. */
class MediaStoreSource(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun query(includeVideos: Boolean = true): List<DeviceMedia> {
        val result = ArrayList<DeviceMedia>()
        queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, into = result)
        if (includeVideos) queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, into = result)
        result.sortByDescending { it.dateTaken }
        return result
    }

    fun folders(): List<DeviceFolder> = query(includeVideos = true)
        .groupBy { it.bucketId }
        .map { (id, items) -> DeviceFolder(id, items.first().bucketName, items.size, items.first()) }
        .sortedByDescending { it.count }

    /** Which of these MediaStore ids still exist. */
    fun existing(ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val wanted = ids.toSet()
        return query(includeVideos = true).asSequence().map { it.id }.filter { it in wanted }.toSet()
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean, into: MutableList<DeviceMedia>) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(COLUMN_DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(COLUMN_BUCKET_ID)
            add(COLUMN_BUCKET_NAME)
            if (isVideo) add(COLUMN_DURATION)
        }.toTypedArray()

        val cursor = try {
            resolver.query(collection, projection, null, null, null)
        } catch (e: SecurityException) {
            null // permission revoked in the meantime
        } ?: return

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenCol = c.getColumnIndexOrThrow(COLUMN_DATE_TAKEN)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val bucketIdCol = c.getColumnIndexOrThrow(COLUMN_BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(COLUMN_BUCKET_NAME)
            val durationCol = if (isVideo) c.getColumnIndexOrThrow(COLUMN_DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val size = c.getLong(sizeCol)
                if (size <= 0L) continue // still being written, or broken
                val taken = c.getLong(takenCol).takeIf { it > 0 }
                    ?: (c.getLong(modifiedCol).takeIf { it > 0 } ?: c.getLong(addedCol)) * 1000L
                val name = c.getString(nameCol) ?: "${if (isVideo) "VID" else "IMG"}_$id"
                into += DeviceMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = name,
                    mime = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg",
                    size = size,
                    dateTaken = taken,
                    width = c.getInt(widthCol),
                    height = c.getInt(heightCol),
                    durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L,
                    bucketId = c.getString(bucketIdCol) ?: "",
                    bucketName = c.getString(bucketNameCol) ?: "",
                    isVideo = isVideo,
                )
            }
        }
    }

    /**
     * Opens the original file. With the "media location" permission the GPS position stays inside
     * the photo; without it Android hands out a copy without location.
     */
    @Throws(IOException::class)
    fun openOriginal(media: DeviceMedia): InputStream {
        val uri = if (Build.VERSION.SDK_INT >= 29 && MediaPermissions.hasMediaLocation(context)) {
            MediaStore.setRequireOriginal(media.uri)
        } else {
            media.uri
        }
        return try {
            resolver.openInputStream(uri)
        } catch (e: UnsupportedOperationException) {
            resolver.openInputStream(media.uri)
        } ?: throw FileNotFoundException(media.uri.toString())
    }

    /** Copies a file back into the phone's gallery (Pictures/Scrigno or Movies/Scrigno). Returns its MediaStore id. */
    @Throws(IOException::class)
    fun insert(file: File, name: String, mime: String, dateTaken: Long): Long {
        val isVideo = mime.startsWith("video/")
        return if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val folder = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Scrigno"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(COLUMN_DATE_TAKEN, dateTaken)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("Cannot write $name")).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e as? IOException ?: IOException(e)
            }
            ContentUris.parseId(uri)
        } else {
            insertLegacy(file, name, isVideo, dateTaken)
        }
    }

    @Suppress("DEPRECATION")
    private fun insertLegacy(file: File, name: String, isVideo: Boolean, dateTaken: Long): Long {
        val base = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        val dir = File(base, "Scrigno").apply { mkdirs() }
        var target = File(dir, name)
        var attempt = 1
        while (target.exists()) target = File(dir, RemotePaths.withCollisionSuffix(name, attempt++))
        file.copyTo(target)
        target.setLastModified(dateTaken)

        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        return scanned?.let { ContentUris.parseId(it) } ?: throw IOException("The media scanner did not index $name")
    }

    /** Android 8-10 only (Android 11+ asks the user with a system dialog instead). */
    fun deleteDirectly(uris: List<Uri>): Int = uris.count { uri ->
        try {
            resolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        // Plain column names, valid on every Android version (some constants are API 29+ only).
        const val COLUMN_DATE_TAKEN = "datetaken"
        const val COLUMN_BUCKET_ID = "bucket_id"
        const val COLUMN_BUCKET_NAME = "bucket_display_name"
        const val COLUMN_DURATION = "duration"
    }
}
