package io.github.bitjacker.scrigno.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.data.media.MediaAccess
import io.github.bitjacker.scrigno.data.media.MediaPermissions

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step == 0) Welcome(onNext = { step = 1 }) else Permissions(onNext = onContinue)
        }
    }
}

@Composable
private fun ColumnScope.Welcome(onNext: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Box(
        Modifier
            .size(96.dp)
            .background(Color(0xFF0F766E), CircleShape)
            .align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
    }
    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.onboarding_tagline),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Feature(Icons.Outlined.Lock, stringResource(R.string.onboarding_private_title), stringResource(R.string.onboarding_private_text))
    Feature(Icons.Outlined.Dns, stringResource(R.string.onboarding_server_title), stringResource(R.string.onboarding_server_text))
    Feature(Icons.Outlined.CloudUpload, stringResource(R.string.onboarding_backup_title), stringResource(R.string.onboarding_backup_text))
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("start"),
    ) { Text(stringResource(R.string.action_start)) }
}

@Composable
private fun ColumnScope.Permissions(onNext: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(MediaPermissions.access(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = MediaPermissions.access(context)
    }
    val permissions = remember {
        MediaPermissions.mediaPermissions() + listOfNotNull(MediaPermissions.notificationPermission())
    }

    Spacer(Modifier.height(24.dp))
    Icon(
        Icons.Outlined.PermMedia,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(64.dp)
            .align(Alignment.CenterHorizontally),
    )
    Text(stringResource(R.string.onboarding_permission_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.onboarding_permission_text), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.onboarding_notifications_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (access) {
        MediaAccess.FULL -> Feature(Icons.Outlined.CheckCircle, stringResource(R.string.onboarding_permission_granted), "")
        MediaAccess.PARTIAL -> Text(stringResource(R.string.gallery_partial_access), style = MaterialTheme.typography.bodyMedium)
        MediaAccess.NONE -> Unit
    }
    Spacer(Modifier.height(8.dp))
    if (access != MediaAccess.FULL) {
        Button(
            onClick = { launcher.launch(permissions) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grant"),
        ) { Text(stringResource(R.string.action_allow)) }
    }
    OutlinedButton(
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("continue"),
    ) { Text(stringResource(R.string.action_continue)) }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
