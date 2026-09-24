package io.github.bitjacker.scrigno.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.ui.common.protocolName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onboarding: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: ServerViewModel = viewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }
    val running = test == ConnectionTest.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    if (!onboarding) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onboarding) {
                Text(stringResource(R.string.server_intro), style = MaterialTheme.typography.bodyLarge)
            }

            Text(stringResource(R.string.server_protocol), style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Protocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = form.protocol == protocol,
                        onClick = { viewModel.setProtocol(protocol) },
                        label = { Text(protocolName(protocol)) },
                        modifier = Modifier.testTag("protocol_${protocol.name}"),
                    )
                }
            }
            Text(
                stringResource(protocolHint(form.protocol)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!form.protocol.encrypted) {
                Notice(Icons.Outlined.Warning, stringResource(R.string.server_unencrypted_warning), warning = true)
            }

            OutlinedTextField(
                value = form.host,
                onValueChange = { value -> viewModel.edit { it.copy(host = value) } },
                label = { Text(stringResource(R.string.server_host)) },
                placeholder = { Text(stringResource(R.string.server_host_placeholder)) },
                supportingText = { Text(stringResource(R.string.server_host_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host"),
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = { value -> viewModel.edit { it.copy(port = value.filter(Char::isDigit).take(5)) } },
                label = { Text(stringResource(R.string.server_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("port"),
            )
            if (form.protocol == Protocol.SMB) {
                OutlinedTextField(
                    value = form.share,
                    onValueChange = { value -> viewModel.edit { it.copy(share = value) } },
                    label = { Text(stringResource(R.string.server_share)) },
                    placeholder = { Text(stringResource(R.string.server_share_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share"),
                )
                OutlinedTextField(
                    value = form.domain,
                    onValueChange = { value -> viewModel.edit { it.copy(domain = value) } },
                    label = { Text(stringResource(R.string.server_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = form.username,
                onValueChange = { value -> viewModel.edit { it.copy(username = value) } },
                label = { Text(stringResource(R.string.server_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("username"),
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = { value -> viewModel.edit { it.copy(password = value) } },
                label = { Text(stringResource(R.string.server_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (showPassword) R.string.action_hide_password else R.string.action_show_password),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("password"),
            )
            OutlinedTextField(
                value = form.basePath,
                onValueChange = { value -> viewModel.edit { it.copy(basePath = value) } },
                label = { Text(stringResource(R.string.server_folder)) },
                placeholder = { Text(stringResource(folderPlaceholder(form.protocol))) },
                supportingText = { Text(stringResource(R.string.server_folder_help)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("folder"),
            )
            if (form.protocol.usesTls) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.server_self_signed), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.server_self_signed_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.allowSelfSigned,
                        onCheckedChange = { value -> viewModel.edit { it.copy(allowSelfSigned = value) } },
                    )
                }
            }
            if (form.fingerprint.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.server_fingerprint), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(form.fingerprint, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::forgetFingerprint) { Text(stringResource(R.string.server_fingerprint_forget)) }
                    }
                }
            }

            when (val result = test) {
                ConnectionTest.Idle -> Unit
                ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.server_testing))
                }
                is ConnectionTest.Success -> Notice(
                    Icons.Outlined.CheckCircle,
                    buildString {
                        append(stringResource(R.string.server_test_ok))
                        result.newFingerprint?.let {
                            append("\n\n")
                            append(stringResource(R.string.server_test_new_fingerprint, it))
                        }
                    },
                    tag = "test_ok",
                )
                is ConnectionTest.Failure -> Notice(Icons.Outlined.ErrorOutline, result.message, warning = true, tag = "test_failed")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    enabled = form.isValid && !running,
                    modifier = Modifier.testTag("test"),
                ) { Text(stringResource(R.string.action_test)) }
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = form.isValid && !running,
                    modifier = Modifier.testTag("save"),
                ) { Text(stringResource(R.string.action_save)) }
            }
            if (test is ConnectionTest.Failure && form.isValid) {
                TextButton(onClick = { viewModel.saveAnyway(onDone) }) { Text(stringResource(R.string.action_save_anyway)) }
            }
            if (onboarding) {
                TextButton(onClick = onDone, modifier = Modifier.testTag("skip")) { Text(stringResource(R.string.action_skip_for_now)) }
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, warning: Boolean = false, tag: String = "") {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Row(Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun protocolHint(protocol: Protocol): Int = when (protocol) {
    Protocol.SFTP -> R.string.hint_sftp
    Protocol.FTPS -> R.string.hint_ftps
    Protocol.FTP -> R.string.hint_ftp
    Protocol.SMB -> R.string.hint_smb
    Protocol.WEBDAVS -> R.string.hint_webdavs
    Protocol.WEBDAV -> R.string.hint_webdav
}

private fun folderPlaceholder(protocol: Protocol): Int = when (protocol) {
    Protocol.SMB -> R.string.server_folder_placeholder_smb
    Protocol.WEBDAV, Protocol.WEBDAVS -> R.string.server_folder_placeholder_webdav
    else -> R.string.server_folder_placeholder
}
