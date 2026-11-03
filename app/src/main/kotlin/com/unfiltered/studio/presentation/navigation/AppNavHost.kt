package com.unfiltered.studio.presentation.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.presentation.screens.gallery.GalleryScreen
import com.unfiltered.studio.presentation.screens.gallery.GenerationDetailScreen
import com.unfiltered.studio.presentation.screens.gallery.ImageViewerScreen
import com.unfiltered.studio.presentation.screens.generate.GenerateScreen
import com.unfiltered.studio.presentation.screens.loras.LoraEditScreen
import com.unfiltered.studio.presentation.screens.loras.LoraLibraryScreen
import com.unfiltered.studio.presentation.screens.models.DownloadsScreen
import com.unfiltered.studio.presentation.screens.models.ModelBrowserScreen
import com.unfiltered.studio.presentation.screens.models.ModelDetailScreen
import com.unfiltered.studio.presentation.screens.settings.ProviderEditScreen
import com.unfiltered.studio.presentation.screens.settings.SettingsScreen

private data class TopLevel(val route: Any, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(GenerateRoute, "Generate", Icons.Default.AutoAwesome),
    TopLevel(ModelsRoute, "Models", Icons.Default.ViewInAr),
    TopLevel(LorasRoute, "LoRAs", Icons.Default.Layers),
    TopLevel(GalleryRoute, "Gallery", Icons.Default.PhotoLibrary),
    TopLevel(SettingsRoute, "Settings", Icons.Default.Settings),
)

private fun NavHostController.navigateTopLevel(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppRoot(generateActions: GenerateActions) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val current = topLevel.firstOrNull { t -> destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val showNav = current != null
        Row(Modifier.fillMaxSize()) {
            if (wide && showNav) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    topLevel.forEach { t ->
                        NavigationRailItem(
                            selected = current == t,
                            onClick = { navController.navigateTopLevel(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!wide && showNav) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            topLevel.forEach { t ->
                                NavigationBarItem(
                                    selected = current == t,
                                    onClick = { navController.navigateTopLevel(t.route) },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = GenerateRoute,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                ) {
                    composable<GenerateRoute> {
                        GenerateScreen(
                            onOpenImage = { navController.navigate(ImageViewerRoute(it)) },
                            onOpenGeneration = { navController.navigate(GenerationDetailRoute(it)) },
                            onManageProviders = { navController.navigateTopLevel(SettingsRoute) },
                            onAddProvider = { navController.navigateTopLevel(SettingsRoute) },
                            onBrowseModels = { navController.navigateTopLevel(ModelsRoute) },
                            onOpenLoraLibrary = { navController.navigateTopLevel(LorasRoute) },
                        )
                    }
                    composable<ModelsRoute> {
                        ModelBrowserScreen(
                            onOpenModel = { source, id -> navController.navigate(ModelDetailRoute(source.name, id)) },
                            onOpenDownloads = { navController.navigate(DownloadsRoute) },
                            onUseCheckpoint = { name ->
                                generateActions.request(PendingGenerate(checkpoint = name, message = "Model selected."))
                                navController.navigateTopLevel(GenerateRoute)
                            },
                        )
                    }
                    composable<LorasRoute> {
                        LoraLibraryScreen(
                            onEdit = { navController.navigate(LoraEditRoute(it)) },
                            onBrowse = { navController.navigateTopLevel(ModelsRoute) },
                        )
                    }
                    composable<GalleryRoute> {
                        GalleryScreen(
                            onOpenImage = { navController.navigate(ImageViewerRoute(it)) },
                            onOpenGeneration = { navController.navigate(GenerationDetailRoute(it)) },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            onEditProvider = { navController.navigate(ProviderEditRoute(providerId = it)) },
                            onAddProvider = { type: ProviderType -> navController.navigate(ProviderEditRoute(type = type.name)) },
                            onOpenDownloads = { navController.navigate(DownloadsRoute) },
                        )
                    }
                    composable<ImageViewerRoute> {
                        ImageViewerScreen(
                            onBack = { navController.popBackStack() },
                            onOpenGenerate = { navController.navigateTopLevel(GenerateRoute) },
                        )
                    }
                    composable<GenerationDetailRoute> {
                        GenerationDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOpenImage = { navController.navigate(ImageViewerRoute(it)) },
                            onOpenGenerate = { navController.navigateTopLevel(GenerateRoute) },
                        )
                    }
                    composable<ModelDetailRoute> {
                        ModelDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOpenDownloads = { navController.navigate(DownloadsRoute) },
                            onOpenGenerate = { navController.navigateTopLevel(GenerateRoute) },
                        )
                    }
                    composable<DownloadsRoute> { DownloadsScreen(onBack = { navController.popBackStack() }) }
                    composable<LoraEditRoute> { LoraEditScreen(onBack = { navController.popBackStack() }) }
                    composable<ProviderEditRoute> { ProviderEditScreen(onBack = { navController.popBackStack() }) }
                }
            }
        }
    }
}
