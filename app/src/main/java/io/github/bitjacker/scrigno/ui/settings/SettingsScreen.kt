package io.github.bitjacker.scrigno.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.data.settings.AppSettings
import io.github.bitjacker.scrigno.ui.common.ChoiceDialog
import io.github.bitjacker.scrigno.ui.common.intervalLabel
import io.github.bitjacker.scrigno.ui.common.keepLabel
import io.github.bitjacker.scrigno.ui.common.pluralString
import io.github.bitjacker.scrigno.ui.common.protocolName
import io.github.bitjacker.scrigno.util.Formatters

private enum class SettingsDialog { INTERVAL, HOUR, KEEP, CACHE, DEVICE_FOLDER, RESET }

private val SmallSpinner: @Composable () -> Unit = {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    outerPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenServer: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    val rebuilding by viewModel.rebuilding.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = outerPadding.calculateBottomPadding()),
    ) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section(stringResource(R.string.section_server))
            Item(
                icon = Icons.Outlined.Dns,
                title = stringResource(R.string.setting_server),
                summary = server?.let { "${protocolName(it.protocol)} · ${it.host}" } ?: stringResource(R.string.backup_no_server),
                onClick = onOpenServer,
            )

            Section(stringResource(R.string.section_backup))
            SwitchItem(
                icon = Icons.Outlined.Sync,
                title = stringResource(R.string.setting_auto_backup),
                summary = stringResource(R.string.setting_auto_backup_summary),
                checked = settings.autoBackup,
                onChange = { value -> viewModel.update(reschedule = true) { it.copy(autoBackup = value) } },
            )
            Item(
                icon = Icons.Outlined.Timer,
                title = stringResource(R.string.setting_interval),
                summary = intervalLabel(settings.intervalHours),
                onClick = { dialog = SettingsDialog.INTERVAL },
            )
            Item(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.setting_hour),
                summary = if (settings.intervalHours >= 24) {
                    Formatters.hour(settings.preferredHour)
                } else {
                    stringResource(R.string.setting_hour_not_used)
                },
                enabled = settings.intervalHours >= 24,
                onClick = { dialog = SettingsDialog.HOUR },
            )
            SwitchItem(
                icon = Icons.Outlined.Wifi,
                title = stringResource(R.string.setting_wifi_only),
                summary = stringResource(R.string.setting_wifi_only_summary),
                checked = settings.wifiOnly,
                onChange = { value -> viewModel.update(reschedule = true) { it.copy(wifiOnly = value) } },
            )
            SwitchItem(
                icon = Icons.Outlined.BatteryChargingFull,
                title = stringResource(R.string.setting_charging_only),
                summary = stringResource(R.string.setting_charging_only_summary),
                checked = settings.chargingOnly,
                onChange = { value -> viewModel.update(reschedule = true) { it.copy(chargingOnly = value) } },
            )
            SwitchItem(
                icon = Icons.Outlined.Videocam,
                title = stringResource(R.string.setting_videos),
                summary = stringResource(R.string.setting_videos_summary),
                checked = settings.includeVideos,
                onChange = { value -> viewModel.update { it.copy(includeVideos = value) } },
            )
            Item(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.setting_folders),
                summary = if (settings.allFolders) {
                    stringResource(R.string.setting_folders_all)
                } else {
                    pluralString(R.plurals.setting_folders_some, settings.selectedFolders.size, settings.selectedFolders.size)
                },
                onClick = onOpenFolders,
            )
            Item(
                icon = Icons.Outlined.Edit,
                title = stringResource(R.string.setting_device_folder),
                summary = settings.deviceFolder.ifBlank { viewModel.defaultDeviceFolder },
                onClick = { dialog = SettingsDialog.DEVICE_FOLDER },
            )

            Section(stringResource(R.string.section_space))
            Item(
                icon = Icons.Outlined.DeleteSweep,
                title = stringResource(R.string.setting_keep_on_phone),
                summary = keepLabel(settings.keepOnDeviceDays),
                onClick = { dialog = SettingsDialog.KEEP },
            )
            SwitchItem(
                icon = Icons.Outlined.NotificationsActive,
                title = stringResource(R.string.setting_reminder),
                summary = stringResource(R.string.setting_reminder_summary),
                checked = settings.freeSpaceReminder,
                enabled = settings.keepOnDeviceDays >= 0,
                onChange = { value -> viewModel.update { it.copy(freeSpaceReminder = value) } },
            )
            Item(
                icon = Icons.Outlined.SdStorage,
                title = stringResource(R.string.setting_cache),
                summary = stringResource(
                    R.string.setting_cache_summary,
                    Formatters.size(context, cacheSize),
                    Formatters.size(context, settings.cacheLimitMb * 1024L * 1024L),
                ),
                onClick = { dialog = SettingsDialog.CACHE },
            )
            Item(
                icon = Icons.Outlined.DeleteForever,
                title = stringResource(R.string.setting_clear_cache),
                summary = stringResource(R.string.setting_clear_cache_summary),
                onClick = viewModel::clearCache,
            )

            Section(stringResource(R.string.section_advanced))
            Item(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.setting_rebuild),
                summary = stringResource(R.string.setting_rebuild_summary),
                enabled = server != null && !rebuilding,
                trailing = if (rebuilding) SmallSpinner else null,
                onClick = viewModel::rebuildIndex,
            )
            Item(
                icon = Icons.Outlined.DeleteForever,
                title = stringResource(R.string.setting_reset),
                summary = stringResource(R.string.setting_reset_summary),
                onClick = { dialog = SettingsDialog.RESET },
            )

            Section(stringResource(R.string.section_about))
            Item(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.setting_about),
                summary = stringResource(R.string.setting_about_summary),
                onClick = onOpenAbout,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    when (dialog) {
        SettingsDialog.INTERVAL -> ChoiceDialog(
            title = stringResource(R.string.setting_interval),
            options = AppSettings.INTERVAL_OPTIONS,
            selected = settings.intervalHours,
            label = { intervalLabel(it) },
            onSelect = { value -> viewModel.update(reschedule = true) { it.copy(intervalHours = value) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.HOUR -> ChoiceDialog(
            title = stringResource(R.string.setting_hour),
            options = (0..23).toList(),
            selected = settings.preferredHour,
            label = { Formatters.hour(it) },
            onSelect = { value -> viewModel.update(reschedule = true) { it.copy(preferredHour = value) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.KEEP -> ChoiceDialog(
            title = stringResource(R.string.setting_keep_on_phone),
            options = AppSettings.KEEP_OPTIONS,
            selected = settings.keepOnDeviceDays,
            label = { keepLabel(it) },
            onSelect = { value -> viewModel.update { it.copy(keepOnDeviceDays = value) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CACHE -> ChoiceDialog(
            title = stringResource(R.string.setting_cache),
            options = AppSettings.CACHE_OPTIONS,
            selected = settings.cacheLimitMb,
            label = { Formatters.size(context, it * 1024L * 1024L) },
            onSelect = { value -> viewModel.update { it.copy(cacheLimitMb = value) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DEVICE_FOLDER -> DeviceFolderDialog(
            current = settings.deviceFolder.ifBlank { viewModel.defaultDeviceFolder },
            onSave = { value -> viewModel.update { it.copy(deviceFolder = value.trim()) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.RESET -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.reset_title)) },
            text = { Text(stringResource(R.string.reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    viewModel.resetApp { (context as? Activity)?.recreate() }
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun DeviceFolderDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_device_folder)) },
        text = {
            Column {
                Text(stringResource(R.string.setting_device_folder_help), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(value)
                    onDismiss()
                },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = trailing,
    )
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}
