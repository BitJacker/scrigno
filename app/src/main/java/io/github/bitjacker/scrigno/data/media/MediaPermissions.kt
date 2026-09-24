package io.github.bitjacker.scrigno.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccess { FULL, PARTIAL, NONE }

/** Which permissions the app asks for, depending on the Android version. */
object MediaPermissions {

    /** Everything needed to read (and on old Android versions delete) photos and videos. */
    fun mediaPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    /**
     * FULL only when every photo and video is visible: the app then knows that a photo it cannot see
     * any more was really deleted. Photos without videos (or the reverse) count as PARTIAL.
     */
    fun access(context: Context): MediaAccess {
        if (Build.VERSION.SDK_INT < 33) {
            return if (granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.NONE
        }
        val images = granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        val videos = granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        return when {
            images && videos -> MediaAccess.FULL
            images || videos -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            else -> MediaAccess.NONE
        }
    }

    fun hasMediaLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
