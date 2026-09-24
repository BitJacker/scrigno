package io.github.bitjacker.scrigno.ui.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bitjacker.scrigno.MainActivity
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.ui.backup.BackupScreen
import io.github.bitjacker.scrigno.ui.gallery.GalleryScreen
import io.github.bitjacker.scrigno.ui.gallery.GalleryViewModel
import io.github.bitjacker.scrigno.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow

enum class HomeTab(@StringRes val label: Int, val icon: ImageVector) {
    PHOTOS(R.string.tab_photos, Icons.Outlined.PhotoLibrary),
    BACKUP(R.string.tab_backup, Icons.Outlined.CloudUpload),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

@Composable
fun HomeScreen(
    galleryViewModel: GalleryViewModel,
    requestedTab: StateFlow<String?>,
    onTabShown: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.PHOTOS) }
    val requested by requestedTab.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(requested) {
        if (requested == MainActivity.TAB_BACKUP) {
            tab = HomeTab.BACKUP
            onTabShown()
        }
    }
    // New photos taken while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { galleryViewModel.refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.PHOTOS -> GalleryScreen(galleryViewModel, padding, onOpenItem)
            HomeTab.BACKUP -> BackupScreen(padding, snackbar, onOpenServer)
            HomeTab.SETTINGS -> SettingsScreen(padding, snackbar, onOpenServer, onOpenFolders, onOpenAbout)
        }
    }
}
