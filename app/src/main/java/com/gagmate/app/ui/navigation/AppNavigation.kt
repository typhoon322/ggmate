package com.gagmate.app.ui.navigation
import androidx.compose.ui.res.stringResource
import com.gagmate.app.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShowChart
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
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.ui.dashboard.DashboardScreen
import com.gagmate.app.ui.dashboard.LiveCurveScreen
import com.gagmate.app.ui.profiles.ProfilesScreen
import com.gagmate.app.ui.profiles.ProfileDetailScreen
import com.gagmate.app.ui.settings.SettingsScreen
import com.gagmate.app.ui.components.DebugOverlay
import com.gagmate.app.ui.components.ShotChartFullScreen
import com.gagmate.app.ui.components.WsDataOverlay
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
    /** Full-screen live curve (not a bottom-tab destination). */
    data object LiveCurve : Screen("livecurve", R.string.dashboard_live_chart, Icons.Default.ShowChart)
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

    // Auto-jump to the live curve screen when a brew starts on the machine
    // (the machine's brew switch is the only thing that can start a shot).
    // Wakes from any tab; we only skip if the user is already on a full-screen
    // chart (live curve / history detail) so we never yank them off an active view.
    LaunchedEffect(Unit) {
        var wasBrewing = false
        AppContainer.machineSession.brewActive.collect { active ->
            val route = navController.currentDestination?.route
            val alreadyOnChart = route == Screen.LiveCurve.route ||
                (route != null && route.startsWith("history_chart"))
            if (active && !wasBrewing && !alreadyOnChart) {
                navController.navigate(Screen.LiveCurve.route) { launchSingleTop = true }
            }
            wasBrewing = active
        }
    }

    Box {
        Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            // 全屏目的地（实时曲线、横屏图表）不显示底部 Tab 栏，把空间留给图表
            val showBottomBar = bottomNavItems.any { it.route == currentRoute }
            if (showBottomBar) {
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
                ProfilesScreen(
                    onOpenProfile = { profileId ->
                        navController.navigate("profile_detail/$profileId") { launchSingleTop = true }
                    }
                )
            }
            composable("profile_detail/{profileId}") { backStackEntry ->
                val profileId = backStackEntry.arguments?.getString("profileId") ?: ""
                ProfileDetailScreen(profileId = profileId, onBack = { navController.popBackStack() })
            }
            composable(Screen.History.route) {
                ShotHistoryScreen(
                    onOpenChart = { shotId ->
                        navController.navigate("history_chart/$shotId") { launchSingleTop = true }
                    }
                )
            }
            composable(Screen.LiveCurve.route) {
                LiveCurveScreen(onBack = { navController.popBackStack() })
            }
            composable("history_chart/{shotId}") { backStackEntry ->
                val shotId = backStackEntry.arguments?.getString("shotId") ?: ""
                ShotChartFullScreen(shotId = shotId, onClose = { navController.popBackStack() })
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
    WsDataOverlay()
    DebugOverlay()
}
