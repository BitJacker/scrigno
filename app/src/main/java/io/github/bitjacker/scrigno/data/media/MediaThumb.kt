package io.github.bitjacker.scrigno.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import java.io.IOException

/** Model for Coil: the small preview Android already keeps for a photo or video of the gallery. */
data class MediaThumb(val uri: Uri, val id: Long, val isVideo: Boolean)

/** Loads MediaStore thumbnails: much faster than decoding a 12 megapixel photo for a grid cell. */
class MediaThumbFetcher(
    private val context: Context,
    private val data: MediaThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = load() ?: throw IOException("No preview for ${data.uri}")
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    @Suppress("DEPRECATION")
    private fun load(): Bitmap? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 29) {
            resolver.loadThumbnail(data.uri, Size(SIZE, SIZE), null)
        } else if (data.isVideo) {
            MediaStore.Video.Thumbnails.getThumbnail(resolver, data.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(resolver, data.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(context, data)
    }

    companion object {
        const val SIZE = 384
    }
}

class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = "thumb:${data.uri}"
}
