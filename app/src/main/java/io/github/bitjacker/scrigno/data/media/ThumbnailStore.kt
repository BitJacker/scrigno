package io.github.bitjacker.scrigno.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File

/**
 * Small JPEG previews (a few tens of KB) of the photos that live only on the server, so the gallery
 * can show them instantly and without a connection, like "optimized storage" on other phones.
 */
class ThumbnailStore(private val context: Context) {

    private val dir = File(context.filesDir, "thumbs")

    fun fileFor(recordId: Long) = File(dir, "$recordId.jpg")

    /** Saves the preview of a photo that is still on the phone. Returns the file path, or null. */
    @Suppress("DEPRECATION")
    fun saveFromDevice(recordId: Long, media: DeviceMedia): String? = runCatching {
        val resolver = context.contentResolver
        val bitmap = if (Build.VERSION.SDK_INT >= 29) {
            resolver.loadThumbnail(media.uri, Size(SIZE, SIZE), null)
        } else if (media.isVideo) {
            MediaStore.Video.Thumbnails.getThumbnail(resolver, media.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(resolver, media.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
        bitmap?.let { write(recordId, it) }
    }.getOrNull()

    /** Saves the preview of a file downloaded from the server. */
    @Suppress("DEPRECATION")
    fun saveFromFile(recordId: Long, file: File, isVideo: Boolean): String? = runCatching {
        val bitmap = if (Build.VERSION.SDK_INT >= 29) {
            if (isVideo) {
                ThumbnailUtils.createVideoThumbnail(file, Size(SIZE, SIZE), null)
            } else {
                ThumbnailUtils.createImageThumbnail(file, Size(SIZE, SIZE), null)
            }
        } else if (isVideo) {
            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
        } else {
            decodeSampled(file)
        }
        bitmap?.let { write(recordId, it) }
    }.getOrNull()

    fun delete(recordId: Long) {
        fileFor(recordId).delete()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun write(recordId: Long, bitmap: Bitmap): String {
        dir.mkdirs()
        val target = fileFor(recordId)
        val temp = File(dir, "$recordId.tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target.absolutePath
    }

    /** Android 8-9: decode a reduced version of the photo and rotate it as the camera intended. */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scaled = ThumbnailUtils.extractThumbnail(bitmap, SIZE, SIZE)
        if (rotation == 0f) return scaled
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private companion object {
        const val SIZE = 384
    }
}
