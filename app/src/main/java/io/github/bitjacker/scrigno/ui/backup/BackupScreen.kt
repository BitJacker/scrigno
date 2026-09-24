package io.github.bitjacker.scrigno.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.backup.BackupState
import io.github.bitjacker.scrigno.backup.FreeSpaceManager
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.data.settings.AppSettings
import io.github.bitjacker.scrigno.ui.common.ChoiceDialog
import io.github.bitjacker.scrigno.ui.common.SystemDeleteLauncher
import io.github.bitjacker.scrigno.ui.common.keepLabel
import io.github.bitjacker.scrigno.ui.common.pluralString
import io.github.bitjacker.scrigno.ui.common.protocolName
import io.github.bitjacker.scrigno.ui.common.scheduleSummary
import io.github.bitjacker.scrigno.ui.common.serverLocation
import io.github.bitjacker.scrigno.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    outerPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenServer: () -> Unit,
    viewModel: BackupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val freeing by viewModel.freeing.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()
    var confirmFree by remember { mutableStateOf(false) }
    var chooseKeep by remember { mutableStateOf(false) }

    SystemDeleteLauncher(deleteRequest, onLaunched = viewModel::onDeleteRequestShown, onResult = viewModel::onDeleteResult)
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = outerPadding.calculateBottomPadding()),
    ) {
        TopAppBar(title = { Text(stringResource(R.string.tab_backup)) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerCard(state.server, state.deviceFolder, onOpenServer)
            if (state.server != null) {
                StatusCard(state, onBackupNow = viewModel::backupNow, onStop = viewModel::stop)
                FreeSpaceCard(
                    state = state,
                    freeing = freeing,
                    onFree = { confirmFree = true },
                    onChooseKeep = { chooseKeep = true },
                )
                AutoBackupCard(state.settings, onToggle = viewModel::setAutoBackup)
            }
            PrivacyNote()
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmFree) {
        val candidates = state.freeCandidates.orEmpty()
        val count = minOf(candidates.size, FreeSpaceManager.MAX_PER_REQUEST)
        val bytes = candidates.take(count).sumOf { it.media.size }
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { confirmFree = false },
            title = { Text(stringResource(R.string.free_space_confirm_title)) },
            text = { Text(pluralString(R.plurals.free_space_confirm_text, count, count, Formatters.size(context, bytes))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFree = false
                    viewModel.freeUpSpace()
                }) { Text(stringResource(R.string.action_free_space)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFree = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (chooseKeep) {
        ChoiceDialog(
            title = stringResource(R.string.setting_keep_on_phone),
            options = AppSettings.KEEP_OPTIONS,
            selected = state.settings.keepOnDeviceDays,
            label = { keepLabel(it) },
            onSelect = viewModel::setKeepDays,
            onDismiss = { chooseKeep = false },
        )
    }
}

@Composable
private fun ServerCard(server: ServerConfig?, deviceFolder: String, onOpenServer: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
            headlineContent = {
                Text(
                    if (server == null) {
                        stringResource(R.string.backup_no_server)
                    } else {
                        "${protocolName(server.protocol)} · ${server.host}"
                    },
                )
            },
            supportingContent = {
                Text(
                    if (server == null) stringResource(R.string.backup_no_server_text) else serverLocation(server, deviceFolder),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                TextButton(onClick = onOpenServer) {
                    Text(stringResource(if (server == null) R.string.action_configure else R.string.action_edit))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(state: BackupUiState, onBackupNow: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val status = state.status) {
                is BackupState.Running -> {
                    Text(stringResource(R.string.backup_running_title), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.backup_running_progress, minOf(status.done + 1, status.total), status.total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    status.currentName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
                }
                BackupState.Idle -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat(state.library.deviceCount, stringResource(R.string.backup_stat_on_phone))
                        Stat(state.safeCount, stringResource(R.string.backup_stat_safe))
                        Stat(state.pendingCount, stringResource(R.string.backup_stat_pending))
                    }
                    val last = state.lastBackup
                    Text(
                        when {
                            last == null -> stringResource(R.string.backup_never)
                            last.error != null -> stringResource(R.string.backup_last_error, Formatters.relative(context, last.finishedAt), last.error)
                            else -> pluralString(R.plurals.backup_last_ok, last.uploaded, Formatters.relative(context, last.finishedAt), last.uploaded)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (last?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.waitingForNetwork) {
                        Text(
                            stringResource(R.string.backup_waiting_network),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(onClick = onBackupNow, enabled = !state.waitingForNetwork) {
                        Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_backup_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FreeSpaceCard(
    state: BackupUiState,
    freeing: FreeingState,
    onFree: () -> Unit,
    onChooseKeep: () -> Unit,
) {
    val context = LocalContext.current
    val days = state.settings.keepOnDeviceDays
    val candidates = state.freeCandidates
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.free_space_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(R.string.free_space_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onChooseKeep)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.setting_keep_on_phone), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(keepLabel(days), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            when {
                days < 0 -> Text(stringResource(R.string.free_space_off), style = MaterialTheme.typography.bodySmall)
                candidates == null -> Text(stringResource(R.string.free_space_computing), style = MaterialTheme.typography.bodySmall)
                candidates.isEmpty() -> Text(stringResource(R.string.free_space_nothing), style = MaterialTheme.typography.bodySmall)
                else -> Text(
                    pluralString(
                        R.plurals.free_space_available,
                        candidates.size,
                        candidates.size,
                        Formatters.size(context, candidates.sumOf { it.media.size }),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (freeing) {
                is FreeingState.Verifying -> {
                    Text(stringResource(R.string.free_space_verifying, freeing.done, freeing.total), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (freeing.total == 0) 0f else freeing.done.toFloat() / freeing.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FreeingState.Deleting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.free_space_waiting_confirmation), style = MaterialTheme.typography.bodySmall)
                }
                FreeingState.Idle -> Button(
                    onClick = onFree,
                    enabled = days >= 0 && !candidates.isNullOrEmpty() && state.status is BackupState.Idle,
                ) {
                    Text(stringResource(R.string.action_free_space))
                }
            }
        }
    }
}

@Composable
private fun AutoBackupCard(settings: AppSettings, onToggle: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.setting_auto_backup)) },
            supportingContent = {
                Text(if (settings.autoBackup) scheduleSummary(settings) else stringResource(R.string.auto_backup_off))
            },
            trailingContent = { Switch(checked = settings.autoBackup, onCheckedChange = onToggle) },
        )
    }
}

@Composable
private fun PrivacyNote() {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.privacy_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
