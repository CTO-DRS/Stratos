package app.stratos.host.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.stratos.host.R
import app.stratos.host.ui.screens.AiBuilderScreen
import app.stratos.host.ui.screens.DashboardScreen
import app.stratos.host.ui.screens.DomainWizardScreen
import app.stratos.host.ui.screens.EditorScreen
import app.stratos.host.ui.screens.MetricsScreen
import app.stratos.host.ui.screens.SettingsScreen
import app.stratos.host.ui.screens.SitesScreen
import app.stratos.host.ui.screens.TunnelScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val SITES = "sites"
    const val EDITOR = "editor/{siteId}"
    const val AI = "ai"
    const val METRICS = "metrics"
    const val TUNNEL = "tunnel"
    const val SETTINGS = "settings"
    const val DOMAIN_WIZARD = "domain_wizard"

    fun editor(siteId: String) = "editor/$siteId"
}

private data class TopDest(
    val route: String,
    val labelRes: Int,
    val filled: ImageVector,
    val outlined: ImageVector
)

@Composable
fun StratosAppUi() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val tops = listOf(
        TopDest(Routes.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Dns, Icons.Outlined.Dns),
        TopDest(Routes.SITES, R.string.nav_sites, Icons.Filled.Storage, Icons.Outlined.Storage),
        TopDest(Routes.AI, R.string.nav_ai, Icons.Filled.SmartToy, Icons.Outlined.SmartToy),
        TopDest(Routes.METRICS, R.string.nav_metrics, Icons.Filled.Insights, Icons.Outlined.Insights),
        TopDest(Routes.TUNNEL, R.string.nav_tunnel, Icons.Filled.VpnKey, Icons.Outlined.VpnKey)
    )

    Scaffold(
        bottomBar = {
            if (tops.any { it.route == current }) {
                NavigationBar {
                    tops.forEach { dest ->
                        val selected = current == dest.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) dest.filled else dest.outlined,
                                    contentDescription = stringResource(dest.labelRes)
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.DASHBOARD) { DashboardScreen(nav) }
            composable(Routes.SITES) { SitesScreen(nav) }
            composable(Routes.EDITOR) { entry ->
                EditorScreen(nav, entry.arguments?.getString("siteId") ?: "")
            }
            composable(Routes.AI) { AiBuilderScreen(nav) }
            composable(Routes.METRICS) { MetricsScreen(nav) }
            composable(Routes.TUNNEL) { TunnelScreen(nav) }
            composable(Routes.DOMAIN_WIZARD) { DomainWizardScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
        }
    }
}

/** Opens settings from anywhere (top-bar gear on dashboard). */
@Composable
fun NavHostController.openSettings() {
    navigate(Routes.SETTINGS) { launchSingleTop = true }
}
