package io.github.bitjacker.scrigno

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import io.github.bitjacker.scrigno.backup.Notifications
import io.github.bitjacker.scrigno.data.media.MediaThumbFetcher
import io.github.bitjacker.scrigno.data.media.MediaThumbKeyer

class ScrignoApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Make sure the nightly job matches the saved settings (e.g. after an update).
        container.scheduler.apply(container.settings.settings.value, container.settings.server.value, replace = false)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(MediaThumbFetcher.Factory(this@ScrignoApp))
            add(MediaThumbKeyer())
            add(VideoFrameDecoder.Factory())
        }
        .crossfade(true)
        .build()
}

val Context.container: AppContainer
    get() = (applicationContext as ScrignoApp).container
