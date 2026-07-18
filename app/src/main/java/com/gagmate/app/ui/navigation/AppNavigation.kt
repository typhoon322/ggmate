package com.gagmate.app.ui.navigation
import androidx.compose.ui.res.stringResource
import com.gagmate.app.R
import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gagmate.app.ui.dashboard.DashboardScreen
import com.gagmate.app.ui.profiles.ProfilesScreen
import com.gagmate.app.ui.settings.SettingsScreen
import com.gagmate.app.ui.components.DebugOverlay
import com.gagmate.app.ui.history.ShotHistoryScreen
import com.gagmate.app.ui.settings.SettingsViewModel

/**
 * Bottom navigation destinations.
 */
sealed class Screen(
    val route: String,
    @androidx.annotation.StringRes val titleRes: Int,
    val icon: ImageVector
) {
    data object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Default.Dashboard)
    data object Profiles : Screen("profiles", R.string.nav_profiles, Icons.Default.List)
    data object History : Screen("history", R.string.nav_history, Icons.Default.History)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

private val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Profiles,
    Screen.History,
    Screen.Settings
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Box {
        Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                        label = { Text(stringResource(screen.titleRes)) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Profiles.route) {
                ProfilesScreen()
            }
            composable(Screen.History.route) {
                ShotHistoryScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
    DebugOverlay()
}
