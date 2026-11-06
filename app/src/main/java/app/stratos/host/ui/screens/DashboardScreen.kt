package app.stratos.host.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.data.SiteEntity
import app.stratos.host.engine.EngineKind
import app.stratos.host.host.HostState
import app.stratos.host.service.HostingService
import app.stratos.host.ui.Routes
import app.stratos.host.ui.components.MonoText
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.components.StatCard
import app.stratos.host.ui.components.StatusDot
import app.stratos.host.ui.theme.GlowButton
import app.stratos.host.ui.theme.LogoTile
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.ui.theme.stratosGlass
import app.stratos.host.util.Formatters
import app.stratos.host.util.QrCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {
    private val graph = StratosApp.instance.graph

    val state = graph.controller.state
    val status = graph.controller.status
    val defaultSite: StateFlow<SiteEntity?> = MutableStateFlow<SiteEntity?>(null)
        .also { flow ->
            viewModelScope.launch {
                graph.repository.observeAll().collect { sites ->
                    flow.value = sites.firstOrNull { it.isDefault } ?: sites.firstOrNull()
                }
            }
        }.asStateFlow()

    fun start() {
        viewModelScope.launch {
            val res = graph.controller.start()
            res.onSuccess {
                HostingService.start(StratosApp.instance)
            }.onFailure {
                HostingService.start(StratosApp.instance) // keep state visible via notification
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            graph.controller.stop()
            HostingService.stop(StratosApp.instance)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavHostController, vm: DashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val status by vm.status.collectAsState()
    val defaultSite by vm.defaultSite.collectAsState()

    var showQr by remember { mutableStateOf(false) }
    val lanUrl = (state as? HostState.Running)?.lanUrl

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogoTile(sizeDp = 28)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    if ((state as? HostState.Running)?.engineKind == EngineKind.KOTLIN) {
                        Pill("KOTLIN", StratosColors.WarningSoft, StratosColors.Warning)
                    } else if (state is HostState.Running) {
                        Pill("GO", StratosColors.SuccessSoft, StratosColors.Success)
                    }
                }
            },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // ---- Status hero card ----
            SectionCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (dotColor, label) = when (state) {
                            is HostState.Running -> StratosColors.Success to stringResource(R.string.state_running)
                            is HostState.Starting, is HostState.Stopping ->
                                StratosColors.Warning to stringResource(R.string.state_transition)
                            is HostState.Failed -> StratosColors.Error to stringResource(R.string.state_failed)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.state_stopped)
                        }
                        StatusDot(dotColor)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        status?.uptimeSec?.takeIf { it > 0 && state is HostState.Running }?.let {
                            Pill(Formatters.duration(it))
                        }
                    }

                    val starting = state is HostState.Starting || state is HostState.Stopping
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val running = state is HostState.Running
                        if (running) {
                            Button(
                                onClick = { vm.stop() },
                                enabled = !starting,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.stop_hosting)) }
                        } else {
                            GlowButton(
                                onClick = { vm.start() },
                                enabled = !starting,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (starting) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    stringResource(R.string.start_hosting),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    lanUrl?.let { url ->
                        UrlRow(url)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showQr = true }, enabled = true) {
                                Icon(Icons.Filled.QrCode2, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.qr))
                            }
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }) {
                                Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.open))
                            }
                        }
                    }

                    (state as? HostState.Failed)?.reason?.let { reason ->
                        Text(
                            reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- Quick stats ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    stringResource(R.string.stat_requests),
                    Formatters.count(status?.requests ?: 0),
                    Modifier.weight(1f)
                )
                StatCard(
                    stringResource(R.string.stat_active),
                    Formatters.count(status?.active ?: 0),
                    Modifier.weight(1f),
                    valueColor = StratosColors.Accent
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    stringResource(R.string.stat_p50),
                    Formatters.ms(status?.p50Ms ?: 0.0),
                    Modifier.weight(1f)
                )
                StatCard(
                    stringResource(R.string.stat_p95),
                    Formatters.ms(status?.p95Ms ?: 0.0),
                    Modifier.weight(1f)
                )
            }

            // ---- Default site ----
            SectionCard(
                header = {
                    Text(stringResource(R.string.default_site), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.SITES) }) {
                        Text(
                            stringResource(R.string.manage_sites),
                            color = StratosColors.Accent,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (defaultSite == null) {
                        Text(
                            stringResource(R.string.no_default_site),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    } else {
                        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(defaultSite!!.name, style = MaterialTheme.typography.titleMedium)
                            MonoText("/" + defaultSite!!.id + " · " + Formatters.bytes(defaultSite!!.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (defaultSite!!.hasIndex) {
                                    Pill(stringResource(R.string.index_ok), StratosColors.SuccessSoft, StratosColors.Success)
                                } else {
                                    Pill(stringResource(R.string.index_missing), StratosColors.WarningSoft, StratosColors.Warning)
                                }
                                if (defaultSite!!.spa) Pill("SPA")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showQr && lanUrl != null) {
        QrDialog(url = lanUrl, onDismiss = { showQr = false })
    }
}

@Composable
private fun UrlRow(url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .stratosGlass(RoundedCornerShape(14.dp), highlight = false)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoText(url, Modifier.weight(1f))
        IconButton(onClick = {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy), Modifier.size(16.dp))
        }
    }
}

@Composable
private fun QrDialog(url: String, onDismiss: () -> Unit) {
    val bitmap: Bitmap = remember(url) { QrCode.bitmap(url, 480) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = url,
                    modifier = Modifier.size(240.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(8.dp))
                MonoText(url, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
