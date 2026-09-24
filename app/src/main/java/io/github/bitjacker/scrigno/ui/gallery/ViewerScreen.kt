package io.github.bitjacker.scrigno.ui.gallery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.data.library.LibraryItem
import io.github.bitjacker.scrigno.data.library.Location
import io.github.bitjacker.scrigno.ui.common.SystemDeleteLauncher
import io.github.bitjacker.scrigno.util.Formatters
import kotlinx.coroutines.launch

private enum class Confirm { DELETE_DEVICE, DELETE_EVERYWHERE, DELETE_SERVER, REMOVE_FROM_PHONE }

@Composable
fun ViewerScreen(viewModel: GalleryViewModel, startKey: String, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = state.items

    SystemDeleteLauncher(deleteRequest, onLaunched = viewModel::onDeleteRequestShown, onResult = viewModel::onDeleteResult)
    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        LaunchedEffect(state.library.loaded) { if (state.library.loaded) onBack() }
        return
    }

    val initialPage = remember { items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))

    LaunchedEffect(pager.currentPage) { zoomed = false }

    fun open(item: LibraryItem, action: String) {
        scope.launch {
            val uri = viewModel.contentUri(item) ?: return@launch
            startExternal(context, action, uri, item.mime)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pager,
            key = { index -> items.getOrNull(index)?.key ?: index },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            ViewerPage(
                item = item,
                download = item.record?.let { downloads[it.id] },
                onNeedOriginal = { viewModel.ensureOriginal(item) },
                onRetry = { viewModel.retryDownload(item) },
                onTap = { chrome = !chrome },
                onZoomChanged = { zoomed = it },
                onPlay = { open(item, Intent.ACTION_VIEW) },
            )
        }

        if (busy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        AnimatedVisibility(
            visible = chrome && current != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (current != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            Formatters.date(current.dateTaken),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            locationLabel(current.location),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { open(current, Intent.ACTION_SEND) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share), tint = Color.White)
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_info), tint = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome && current != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (current != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .navigationBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    when (current.location) {
                        Location.DEVICE -> {
                            ViewerAction(Icons.Outlined.Delete, stringResource(R.string.viewer_delete)) {
                                if (Build.VERSION.SDK_INT >= 30) viewModel.delete(current, everywhere = false) else confirm = Confirm.DELETE_DEVICE
                            }
                        }
                        Location.BOTH -> {
                            ViewerAction(Icons.Outlined.DeleteSweep, stringResource(R.string.viewer_remove_from_phone)) {
                                if (Build.VERSION.SDK_INT >= 30) viewModel.removeFromPhone(current) else confirm = Confirm.REMOVE_FROM_PHONE
                            }
                            ViewerAction(Icons.Outlined.Delete, stringResource(R.string.viewer_delete_everywhere)) {
                                confirm = Confirm.DELETE_EVERYWHERE
                            }
                        }
                        Location.SERVER -> {
                            ViewerAction(Icons.Outlined.CloudDownload, stringResource(R.string.viewer_restore)) {
                                viewModel.restore(current)
                            }
                            ViewerAction(Icons.Outlined.Delete, stringResource(R.string.viewer_delete_from_server)) {
                                confirm = Confirm.DELETE_SERVER
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfo && current != null) {
        InfoDialog(current, onDismiss = { showInfo = false })
    }

    val pending = confirm
    if (pending != null && current != null) {
        val (title, text) = when (pending) {
            Confirm.DELETE_DEVICE -> R.string.confirm_delete_device_title to R.string.confirm_delete_device_text
            Confirm.DELETE_EVERYWHERE -> R.string.confirm_delete_everywhere_title to R.string.confirm_delete_everywhere_text
            Confirm.DELETE_SERVER -> R.string.confirm_delete_server_title to R.string.confirm_delete_server_text
            Confirm.REMOVE_FROM_PHONE -> R.string.confirm_remove_from_phone_title to R.string.confirm_remove_from_phone_text
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (pending) {
                        Confirm.DELETE_DEVICE -> viewModel.delete(current, everywhere = false)
                        Confirm.DELETE_EVERYWHERE -> viewModel.delete(current, everywhere = true)
                        Confirm.DELETE_SERVER -> viewModel.deleteFromServer(current)
                        Confirm.REMOVE_FROM_PHONE -> viewModel.removeFromPhone(current)
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ViewerPage(
    item: LibraryItem,
    download: DownloadState?,
    onNeedOriginal: () -> Unit,
    onRetry: () -> Unit,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    val onServerOnly = item.location == Location.SERVER
    LaunchedEffect(item.key) {
        if (onServerOnly && !item.isVideo) onNeedOriginal()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableBox(onTap = onTap, onZoomChanged = onZoomChanged) {
            // The small preview first, then the full photo on top as soon as it is ready.
            val preview = item.thumbnailModel
            if (preview != null) {
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (item.device == null) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            }
            val full: Any? = when {
                item.isVideo -> null
                item.device != null -> item.device.uri
                download is DownloadState.Ready -> download.file
                else -> null
            }
            if (full != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(full).size(FULL_SIZE).crossfade(true).build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = stringResource(R.string.action_play),
                tint = Color.White,
                modifier = Modifier
                    .size(72.dp)
                    .clickable(onClick = onPlay),
            )
        }

        when (download) {
            is DownloadState.Loading -> Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.viewer_downloading), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.width(160.dp))
            }
            is DownloadState.Failed -> Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(download.message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            else -> Unit
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InfoDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(stringResource(R.string.info_date), Formatters.dateTime(item.dateTaken))
                InfoRow(stringResource(R.string.info_size), Formatters.size(context, item.size))
                if (item.width > 0 && item.height > 0) {
                    InfoRow(stringResource(R.string.info_resolution), "${item.width} × ${item.height}")
                }
                InfoRow(stringResource(R.string.info_where), locationLabel(item.location))
                item.record?.let { InfoRow(stringResource(R.string.info_server_path), it.remotePath) }
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun locationLabel(location: Location): String = stringResource(
    when (location) {
        Location.DEVICE -> R.string.location_device
        Location.BOTH -> R.string.location_both
        Location.SERVER -> R.string.location_server
    },
)

private fun startExternal(context: Context, action: String, uri: Uri, mime: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (action == Intent.ACTION_SEND) {
        intent.type = mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        intent.setDataAndType(uri, mime)
    }
    try {
        context.startActivity(if (action == Intent.ACTION_SEND) Intent.createChooser(intent, null) else intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_LONG).show()
    }
}

/** Largest side of the decoded photo: sharp when zoomed, without exhausting memory. */
private const val FULL_SIZE = 2560
