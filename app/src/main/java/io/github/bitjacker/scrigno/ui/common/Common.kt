package io.github.bitjacker.scrigno.ui.common

import androidx.annotation.PluralsRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.settings.AppSettings
import io.github.bitjacker.scrigno.util.Formatters

/** Quantity strings ("1 photo", "3 photos") in composables. */
@Composable
fun pluralString(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)

fun protocolName(protocol: Protocol): String = when (protocol) {
    Protocol.SFTP -> "SFTP"
    Protocol.FTPS -> "FTPS"
    Protocol.FTP -> "FTP"
    Protocol.SMB -> "SMB"
    Protocol.WEBDAVS -> "WebDAV (HTTPS)"
    Protocol.WEBDAV -> "WebDAV (HTTP)"
}

/** "nas.local · /photos" style description of where the backups go. */
fun serverLocation(server: ServerConfig, deviceFolder: String): String {
    val base = server.basePath.trim('/')
    return if (server.protocol == Protocol.SMB) {
        listOf(server.host, server.share, base, deviceFolder).filter { it.isNotEmpty() }.joinToString("\\", prefix = "\\\\")
    } else {
        val path = listOf(base, deviceFolder).filter { it.isNotEmpty() }.joinToString("/")
        "${server.host}:${server.port}/" + path
    }
}

@Composable
fun keepLabel(days: Int): String = when {
    days < 0 -> stringResource(R.string.keep_forever)
    days == 0 -> stringResource(R.string.keep_none)
    else -> pluralString(R.plurals.keep_days, days, days)
}

@Composable
fun intervalLabel(hours: Int): String = when {
    hours < 24 -> pluralString(R.plurals.interval_hours, hours, hours)
    hours == 24 -> stringResource(R.string.interval_daily)
    hours % 24 == 0 && hours / 24 == 7 -> stringResource(R.string.interval_weekly)
    else -> pluralString(R.plurals.interval_days, hours / 24, hours / 24)
}

@Composable
fun scheduleSummary(settings: AppSettings): String {
    val parts = mutableListOf(intervalLabel(settings.intervalHours))
    if (settings.intervalHours >= 24) parts += stringResource(R.string.schedule_at_hour, Formatters.hour(settings.preferredHour))
    parts += stringResource(if (settings.wifiOnly) R.string.schedule_wifi_only else R.string.schedule_any_network)
    if (settings.chargingOnly) parts += stringResource(R.string.schedule_charging)
    return parts.joinToString(" · ")
}

/** A list of options with radio buttons, applied as soon as one is tapped. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
