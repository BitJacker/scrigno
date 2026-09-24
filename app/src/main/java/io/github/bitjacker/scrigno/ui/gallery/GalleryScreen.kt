package io.github.bitjacker.scrigno.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.data.library.LibraryItem
import io.github.bitjacker.scrigno.data.library.Location
import io.github.bitjacker.scrigno.data.media.MediaAccess
import io.github.bitjacker.scrigno.data.media.MediaPermissions
import io.github.bitjacker.scrigno.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    outerPadding: PaddingValues,
    onOpenItem: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
    }
    val askPermission = { permissionLauncher.launch(MediaPermissions.mediaPermissions()) }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = outerPadding.calculateBottomPadding()),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.app_name))
                    if (state.filter != GalleryFilter.ALL) {
                        Text(
                            stringResource(state.filter.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.gallery_filter))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        GalleryFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.label)) },
                                onClick = {
                                    viewModel.setFilter(option)
                                    menuOpen = false
                                },
                                leadingIcon = {
                                    if (state.filter == option) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                            )
                        }
                    }
                }
            },
        )

        when {
            !state.library.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.library.access == MediaAccess.NONE && state.items.isEmpty() -> Message(
                title = stringResource(R.string.gallery_permission_title),
                text = stringResource(R.string.gallery_permission_text),
                action = stringResource(R.string.action_allow),
                onAction = askPermission,
            )
            state.items.isEmpty() -> Message(
                title = stringResource(R.string.gallery_empty_title),
                text = stringResource(
                    if (state.filter == GalleryFilter.ALL) R.string.gallery_empty_text else R.string.gallery_empty_filter,
                ),
            )
            else -> {
                if (state.library.access == MediaAccess.PARTIAL) PartialAccessBanner(onChange = askPermission)
                PhotoGrid(entries = state.entries, onOpen = onOpenItem)
            }
        }
    }
}

@Composable
private fun PhotoGrid(entries: List<GridEntry>, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = entries,
            key = { it.key },
            span = { entry -> if (entry is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { entry -> if (entry is GridEntry.Header) 0 else 1 },
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> Text(
                    text = Formatters.monthTitle(entry.month),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
                )
                is GridEntry.Photo -> PhotoTile(entry.item, onClick = { onOpen(entry.item.key) })
            }
        }
    }
}

@Composable
private fun PhotoTile(item: LibraryItem, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        val model = item.thumbnailModel
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Image,
                contentDescription = item.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (item.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (item.durationMs > 0) {
                    Text(
                        Formatters.duration(item.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(shadow = Shadow(Color.Black, blurRadius = 4f)),
                    )
                }
            }
        }
        val badge = when (item.location) {
            Location.SERVER -> Icons.Filled.Cloud
            Location.BOTH -> Icons.Filled.CloudDone
            Location.DEVICE -> null
        }
        if (badge != null) {
            Icon(
                badge,
                contentDescription = stringResource(
                    if (item.location == Location.SERVER) R.string.location_server else R.string.location_both,
                ),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .padding(3.dp),
            )
        }
    }
}

@Composable
private fun PartialAccessBanner(onChange: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.gallery_partial_access),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange) { Text(stringResource(R.string.action_change)) }
        }
    }
}

@Composable
private fun Message(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}
