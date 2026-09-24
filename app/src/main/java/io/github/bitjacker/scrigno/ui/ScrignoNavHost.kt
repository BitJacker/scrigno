package io.github.bitjacker.scrigno.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.bitjacker.scrigno.container
import io.github.bitjacker.scrigno.ui.about.AboutScreen
import io.github.bitjacker.scrigno.ui.gallery.GalleryViewModel
import io.github.bitjacker.scrigno.ui.gallery.ViewerScreen
import io.github.bitjacker.scrigno.ui.home.HomeScreen
import io.github.bitjacker.scrigno.ui.onboarding.OnboardingScreen
import io.github.bitjacker.scrigno.ui.settings.FoldersScreen
import io.github.bitjacker.scrigno.ui.settings.ServerScreen
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val SERVER = "server?onboarding={onboarding}"
    const val HOME = "home"
    const val VIEWER = "viewer/{key}"
    const val FOLDERS = "folders"
    const val ABOUT = "about"

    fun server(onboarding: Boolean) = "server?onboarding=$onboarding"
    fun viewer(key: String) = "viewer/$key"
}

@Composable
fun ScrignoNavHost(requestedTab: StateFlow<String?>, onTabShown: () -> Unit) {
    val container = LocalContext.current.container
    val navController = rememberNavController()
    val start = remember { if (container.settings.settings.value.onboardingDone) Routes.HOME else Routes.ONBOARDING }
    // Shared by the grid and the full screen viewer, so swiping follows the grid order.
    val galleryViewModel: GalleryViewModel = viewModel()

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onContinue = { navController.navigate(Routes.server(onboarding = true)) })
        }
        composable(
            route = Routes.SERVER,
            arguments = listOf(
                navArgument("onboarding") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val onboarding = entry.arguments?.getBoolean("onboarding") ?: false
            ServerScreen(
                onboarding = onboarding,
                onDone = {
                    if (onboarding) {
                        container.settings.update { it.copy(onboardingDone = true) }
                        container.scheduler.apply(container.settings.settings.value, container.settings.server.value, replace = true)
                        container.scheduler.startFirstBackup(container.settings.settings.value, container.settings.server.value)
                        galleryViewModel.refresh()
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                galleryViewModel = galleryViewModel,
                requestedTab = requestedTab,
                onTabShown = onTabShown,
                onOpenItem = { key -> navController.navigate(Routes.viewer(key)) },
                onOpenServer = { navController.navigate(Routes.server(onboarding = false)) },
                onOpenFolders = { navController.navigate(Routes.FOLDERS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(
            route = Routes.VIEWER,
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { entry ->
            ViewerScreen(
                viewModel = galleryViewModel,
                startKey = entry.arguments?.getString("key").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FOLDERS) {
            FoldersScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
