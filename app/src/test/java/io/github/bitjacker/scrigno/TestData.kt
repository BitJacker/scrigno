package io.github.bitjacker.scrigno

import android.net.Uri
import io.github.bitjacker.scrigno.data.db.BackupRecord
import io.github.bitjacker.scrigno.data.media.DeviceMedia
import java.time.LocalDateTime
import java.time.ZoneId

/** Small builders for test data. */
object TestData {

    fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun media(
        id: Long,
        date: Long = millis(2024, 5, 10),
        name: String = "IMG_$id.jpg",
        bucket: String = "camera",
        video: Boolean = false,
        size: Long = 1000L + id,
    ) = DeviceMedia(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        name = name,
        mime = if (video) "video/mp4" else "image/jpeg",
        size = size,
        dateTaken = date,
        width = 4000,
        height = 3000,
        durationMs = if (video) 15_000 else 0,
        bucketId = bucket,
        bucketName = bucket.replaceFirstChar { it.uppercase() },
        isVideo = video,
    )

    fun record(
        id: Long,
        mediaId: Long? = null,
        date: Long = millis(2024, 5, 10),
        server: String = "sftp://me@nas/photos",
        onDevice: Boolean = mediaId != null,
        name: String = "IMG_$id.jpg",
        thumb: String? = null,
    ) = BackupRecord(
        id = id,
        server = server,
        mediaId = mediaId,
        name = name,
        mime = "image/jpeg",
        size = 1000L + id,
        dateTaken = date,
        width = 4000,
        height = 3000,
        durationMs = 0,
        remotePath = "Phone/2024/05/$name",
        uploadedAt = date,
        onDevice = onDevice,
        keepOnDevice = false,
        thumbPath = thumb,
    )
}
