package io.github.bitjacker.scrigno.core.backup

import java.util.Locale

/** Recognises photos and videos on the server by their extension. */
object MediaFiles {
    private val IMAGE_TYPES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "bmp" to "image/bmp", "dng" to "image/x-adobe-dng",
    )
    private val VIDEO_TYPES = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
    )

    private fun extension(name: String): String =
        RemotePaths.splitExtension(name).second.removePrefix(".").lowercase(Locale.ROOT)

    fun mimeType(name: String): String? = extension(name).let { IMAGE_TYPES[it] ?: VIDEO_TYPES[it] }

    fun isMedia(name: String): Boolean = mimeType(name) != null && !RemotePaths.isTempName(name)

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_TYPES
}
