package io.github.bitjacker.scrigno.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.data.media.DeviceFolder
import io.github.bitjacker.scrigno.data.media.MediaThumb
import io.github.bitjacker.scrigno.data.settings.AppSettings
import io.github.bitjacker.scrigno.ui.common.pluralString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FoldersViewModel(application: Application) : AndroidViewModel(application) {
    private val c = application.container

    val settings: StateFlow<AppSettings> = c.settings.settings

    private val _folders = MutableStateFlow<List<DeviceFolder>?>(null)
    val folders: StateFlow<List<DeviceFolder>?> = _folders.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _folders.value = c.mediaStore.folders() }
    }

    fun setAllFolders(all: Boolean) {
        c.settings.update { settings ->
            if (!all && settings.selectedFolders.isEmpty()) {
                // A sensible start: the camera folders.
                val cameras = _folders.value.orEmpty().filter { it.name.equals("Camera", ignoreCase = true) }.map { it.id }
                settings.copy(allFolders = false, selectedFolders = cameras.toSet())
            } else {
                settings.copy(allFolders = all)
            }
        }
    }

    fun toggle(folderId: String) {
        c.settings.update { settings ->
            val selected = if (folderId in settings.selectedFolders) settings.selectedFolders - folderId else settings.selectedFolders + folderId
            settings.copy(selectedFolders = selected)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(onBack: () -> Unit, viewModel: FoldersViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_folders)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val list = folders
        if (list == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(contentPadding = padding) {
            item {
                ListItem(
                    modifier = Modifier.clickable { viewModel.setAllFolders(!settings.allFolders) },
                    headlineContent = { Text(stringResource(R.string.setting_folders_all)) },
                    supportingContent = { Text(stringResource(R.string.folders_all_summary)) },
                    trailingContent = {
                        Switch(checked = settings.allFolders, onCheckedChange = viewModel::setAllFolders)
                    },
                )
                HorizontalDivider()
            }
            items(list, key = { it.id }) { folder ->
                val checked = settings.allFolders || folder.id in settings.selectedFolders
                ListItem(
                    modifier = Modifier.clickable(enabled = !settings.allFolders) { viewModel.toggle(folder.id) },
                    leadingContent = {
                        AsyncImage(
                            model = MediaThumb(folder.cover.uri, folder.cover.id, folder.cover.isVideo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    },
                    headlineContent = { Text(folder.name.ifEmpty { "—" }) },
                    supportingContent = { Text(pluralString(R.plurals.folder_items, folder.count, folder.count)) },
                    trailingContent = {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { viewModel.toggle(folder.id) },
                            enabled = !settings.allFolders,
                        )
                    },
                )
            }
        }
    }
}
