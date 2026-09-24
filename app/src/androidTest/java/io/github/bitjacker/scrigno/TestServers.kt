package io.github.bitjacker.scrigno

import android.Manifest
import android.os.Build
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.ServerConfig

/**
 * The servers to test against, passed by the CI as the instrumentation argument "scrignoServers":
 * URL-safe Base64 of lines "PROTOCOL host port user password share basePath allowSelfSigned"
 * ("-" for an empty field). See scripts/ci/emulator-servers.sh.
 */
object TestServers {

    val all: List<ServerConfig> by lazy {
        val encoded = InstrumentationRegistry.getArguments().getString("scrignoServers").orEmpty()
        if (encoded.isBlank()) return@lazy emptyList()
        val text = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val f = line.split(Regex("\\s+")).map { if (it == "-") "" else it }
            ServerConfig(
                protocol = Protocol.valueOf(f[0]),
                host = f[1],
                port = f[2].toInt(),
                username = f[3],
                password = f[4],
                share = f[5],
                basePath = f[6],
                allowSelfSigned = f.getOrNull(7) == "true",
            )
        }
    }
}

object TestPermissions {
    val all: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()
}

/** Starts from a clean app: no server, no settings, empty index and caches. */
suspend fun resetApp(context: android.content.Context) {
    val c = context.container
    c.scheduler.cancelAll()
    c.database.clear()
    c.thumbnails.clear()
    c.originals.clearCache()
    c.settings.reset()
    c.library.refresh()
}
