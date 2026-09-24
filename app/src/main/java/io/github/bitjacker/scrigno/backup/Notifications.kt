package io.github.bitjacker.scrigno.backup

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.bitjacker.scrigno.MainActivity
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.data.media.MediaPermissions
import io.github.bitjacker.scrigno.util.ErrorMessages
import io.github.bitjacker.scrigno.util.Formatters

/** The few notifications Scrigno shows. They stay on the phone like everything else. */
object Notifications {
    private const val CHANNEL_PROGRESS = "backup_progress"
    private const val CHANNEL_ALERTS = "alerts"

    const val ID_PROGRESS = 1
    const val ID_FREE_SPACE = 2
    const val ID_ERROR = 3
    const val ID_DONE = 4

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun progress(context: Context, state: BackupState.Running?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_scrigno)
            .setContentTitle(context.getString(R.string.notification_backup_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context, MainActivity.TAB_BACKUP))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state == null || state.total == 0) {
            builder.setContentText(context.getString(R.string.notification_backup_preparing))
            builder.setProgress(0, 0, true)
        } else {
            builder.setContentText(
                context.getString(R.string.notification_backup_progress, state.done + 1, state.total),
            )
            state.currentName?.let { builder.setSubText(it) }
            builder.setProgress(1000, (state.fraction * 1000).toInt(), false)
        }
        return builder.build()
    }

    fun backupDone(context: Context, uploaded: Int) {
        val text = context.resources.getQuantityString(R.plurals.notification_backup_done, uploaded, uploaded)
        notify(
            context,
            ID_DONE,
            alert(context, context.getString(R.string.notification_backup_done_title), text, MainActivity.TAB_BACKUP)
                .setTimeoutAfter(60 * 60 * 1000L)
                .build(),
        )
    }

    fun backupFailed(context: Context, error: Throwable) {
        notify(
            context,
            ID_ERROR,
            alert(
                context,
                context.getString(R.string.notification_backup_failed_title),
                ErrorMessages.describe(context, error),
                MainActivity.TAB_BACKUP,
            ).build(),
        )
    }

    fun freeSpace(context: Context, count: Int, bytes: Long) {
        val text = context.resources.getQuantityString(
            R.plurals.notification_free_space_text,
            count,
            count,
            Formatters.size(context, bytes),
        )
        notify(
            context,
            ID_FREE_SPACE,
            alert(context, context.getString(R.string.notification_free_space_title), text, MainActivity.TAB_BACKUP).build(),
        )
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    @SuppressLint("MissingPermission") // checked by canPostNotifications
    fun notify(context: Context, id: Int, notification: Notification) {
        if (!MediaPermissions.canPostNotifications(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun alert(context: Context, title: String, text: String, tab: String) =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_scrigno)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, tab))

    private fun openApp(context: Context, tab: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            tab.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
