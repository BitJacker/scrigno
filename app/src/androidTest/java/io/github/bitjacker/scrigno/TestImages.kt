package io.github.bitjacker.scrigno

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** A photo created by the test, as it was written into the phone's gallery. */
data class TestImage(val uri: Uri, val id: Long, val name: String, val bytes: ByteArray)

/** Creates real JPEG photos (with a capture date in EXIF) in Pictures/<folder>. */
object TestImages {

    fun create(context: Context, folder: String, name: String, exifDate: String, color: Int): TestImage {
        // 1. A small but real JPEG with the capture date, like a camera would write it.
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawCircle(80f, 60f, 30f, android.graphics.Paint().apply { this.color = Color.WHITE })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
            setAttribute(ExifInterface.TAG_DATETIME, exifDate)
            saveAttributes()
        }
        val bytes = file.readBytes()
        file.delete()

        // 2. Into MediaStore, like the camera app does.
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused $name")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return TestImage(uri, ContentUris.parseId(uri), name, bytes)
    }

    /** Deletes the photos of a test folder (the app owns them, so no confirmation is needed). */
    fun deleteFolder(context: Context, folder: String) {
        val resolver = context.contentResolver
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, "bucket_display_name"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == folder || c.getString(1) == "Scrigno") {
                        runCatching { resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null) }
                    }
                }
            }
        }
    }
}
