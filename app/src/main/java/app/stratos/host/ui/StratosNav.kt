package app.stratos.host.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.stratos.host.R
import app.stratos.host.ui.components.clickableNoRipple
import app.stratos.host.ui.screens.AiBuilderScreen
import app.stratos.host.ui.screens.DashboardScreen
import app.stratos.host.ui.screens.DomainWizardScreen
import app.stratos.host.ui.screens.EditorScreen
import app.stratos.host.ui.screens.MetricsScreen
import app.stratos.host.ui.screens.SettingsScreen
import app.stratos.host.ui.screens.SitesScreen
import app.stratos.host.ui.screens.TunnelScreen
import app.stratos.host.ui.theme.AuroraBackdrop
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.ui.theme.glassEdgeBrush
import app.stratos.host.ui.theme.stratosGlowBrush
import app.stratos.host.ui.theme.stratosIsDark

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

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Living aurora behind everything; glass surfaces float above it.
        AuroraBackdrop(Modifier.fillMaxSize())

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (tops.any { it.route == current }) {
                    StratosDock(
                        tops = tops,
                        current = current,
                        onSelect = { dest ->
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
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
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.DOMAIN_WIZARD) { DomainWizardScreen(nav) }
            }
        }
    }
}

// ---- Floating glass dock ----------------------------------------------------

@Composable
private fun StratosDock(
    tops: List<TopDest>,
    current: String?,
    onSelect: (TopDest) -> Unit
) {
    val dark = stratosIsDark()
    val shape = RoundedCornerShape(24.dp)
    val dockFill = if (dark) Color(0xFF0B0E18).copy(alpha = 0.86f)
    else Color.White.copy(alpha = 0.88f)

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Under-glow: a faint energy line breathing beneath the dock.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .height(18.dp)
                .offset(y = 4.dp)
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                (if (dark) StratosColors.GlowB else StratosColors.GlowA)
                                    .copy(alpha = 0.20f),
                                Color.Transparent
                            )
                        )
                    )
                }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(shape)
                .background(dockFill, shape)
                .border(1.dp, glassEdgeBrush(dark), shape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tops.forEach { dest ->
                DockItem(
                    dest = dest,
                    selected = current == dest.route,
                    onSelect = { onSelect(dest) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    dest: TopDest,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(280),
        label = "dockSel"
    )
    val iconTint = lerp(muted, MaterialTheme.colorScheme.onSurface, progress)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickableNoRipple(onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Fixed icon slot: outlined icon fades out while the gradient pill fades in.
        Box(
            modifier = Modifier.height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                dest.outlined,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { alpha = 1f - progress }
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = progress }
                    .clip(RoundedCornerShape(14.dp))
                    .background(stratosGlowBrush())
                    .padding(horizontal = 13.dp, vertical = 5.dp)
            ) {
                Icon(
                    dest.filled,
                    contentDescription = stringResource(dest.labelRes),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(dest.labelRes),
            color = iconTint,
            fontSize = 10.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Opens settings from anywhere (top-bar gear on dashboard). */
fun NavHostController.openSettings() {
    navigate(Routes.SETTINGS) { launchSingleTop = true }
}
