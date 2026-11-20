package io.github.jimthefatjam.polaris.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.components.Starfield
import io.github.jimthefatjam.polaris.ui.explore.ExploreScreen
import io.github.jimthefatjam.polaris.ui.settings.SettingsScreen
import io.github.jimthefatjam.polaris.ui.sky.SkyScreen
import io.github.jimthefatjam.polaris.ui.telescope.TelescopeScreen
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import io.github.jimthefatjam.polaris.ui.tonight.TonightScreen

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    TONIGHT("tonight", "Tonight", Icons.Outlined.NightsStay),
    SKY("sky", "Sky", Icons.Outlined.AutoAwesome),
    EXPLORE("explore", "Explore", Icons.Outlined.TravelExplore),
    TELESCOPE("telescope", "Telescope", Icons.Outlined.Explore),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}

@Composable
fun PolarisRoot(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    fun go(d: Destination) = nav.navigate(d.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Box(Modifier.fillMaxSize()) {
        Starfield(Modifier.fillMaxSize())
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = PolarisColors.Starlight,
            bottomBar = {
                NavigationBar(containerColor = PolarisColors.Night.copy(alpha = 0.92f), tonalElevation = 0.dp) {
                    Destination.entries.forEach { d ->
                        val selected = current?.hierarchy?.any { it.route == d.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { go(d) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(d.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PolarisColors.Night,
                                indicatorColor = PolarisColors.Accent,
                                unselectedIconColor = PolarisColors.Muted,
                                unselectedTextColor = PolarisColors.Muted,
                                selectedTextColor = PolarisColors.Starlight,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Destination.TONIGHT.route, modifier = Modifier.padding(padding)) {
                composable(Destination.TONIGHT.route) {
                    TonightScreen(container, onOpenSettings = { go(Destination.SETTINGS) }, onOpenSky = { go(Destination.SKY) })
                }
                composable(Destination.SKY.route) { SkyScreen(container) }
                composable(Destination.EXPLORE.route) { ExploreScreen(container) }
                composable(Destination.TELESCOPE.route) { TelescopeScreen(container) }
                composable(Destination.SETTINGS.route) { SettingsScreen(container) }
            }
        }
    }
}


