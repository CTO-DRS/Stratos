package app.stratos.host.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.data.Settings
import app.stratos.host.tunnel.TunnelManager
import app.stratos.host.ui.Routes
import app.stratos.host.ui.components.MonoText
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.components.StatusDot
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.ui.theme.stratosGlass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TunnelViewModel : ViewModel() {
    private val graph = StratosApp.instance.graph
    val info = graph.tunnel.info
    val hostState = graph.controller.state
    val settings = graph.settings.settings
    val tail = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                tail.value = graph.tunnel.tailLogs(60)
                kotlinx.coroutines.delay(800)
            }
        }
    }

    fun connectQuick() = viewModelScope.launch {
        val port = currentPort()
        graph.tunnel.connect(TunnelManager.Mode.QUICK, port)
    }

    fun connectNamed() = viewModelScope.launch {
        val port = currentPort()
        graph.tunnel.connect(TunnelManager.Mode.NAMED, port)
    }

    private suspend fun currentPort(): Int =
        graph.settings.settings.first().port.takeIf { it in 1024..65535 } ?: 8080

    fun disconnect() = viewModelScope.launch { graph.tunnel.disconnect() }

    fun saveToken(token: String) = viewModelScope.launch {
        graph.tunnel.saveToken(token.toCharArray())
    }

    fun clearToken() = viewModelScope.launch { graph.tunnel.clearToken() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(nav: NavHostController, vm: TunnelViewModel = viewModel()) {
    val info by vm.info.collectAsState()
    val hostState by vm.hostState.collectAsState()
    val tail by vm.tail.collectAsState()
    val prefs by vm.settings.collectAsState(initial = Settings())
    val context = LocalContext.current

    var tokenDialog by remember { mutableStateOf(false) }
    val hostingOn = hostState is app.stratos.host.host.HostState.Running

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_tunnel)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hostingOn) {
                SectionCard {
                    Text(
                        stringResource(R.string.tunnel_needs_hosting),
                        Modifier.padding(16.dp),
                        color = StratosColors.Warning,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---- Quick tunnel ----
            SectionCard(
                header = {
                    Text(stringResource(R.string.tunnel_quick_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (info.mode == TunnelManager.Mode.QUICK) TunnelPill(info.state)
                }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.tunnel_quick_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    info.publicUrl?.takeIf { info.mode == TunnelManager.Mode.QUICK }?.let { url ->
                        PublicUrlRow(url)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (info.mode == TunnelManager.Mode.QUICK && info.state != TunnelManager.TunnelState.DISCONNECTED) {
                            Button(onClick = { vm.disconnect() }, enabled = hostingOn || info.state != TunnelManager.TunnelState.CONNECTING) {
                                Text(stringResource(R.string.disconnect))
                            }
                        } else {
                            Button(onClick = { vm.connectQuick() }, enabled = hostingOn) {
                                if (info.state == TunnelManager.TunnelState.CONNECTING && info.mode == TunnelManager.Mode.QUICK) {
                                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Filled.Link, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tunnel_quick_connect))
                            }
                        }
                    }
                }
            }

            // ---- Named tunnel ----
            SectionCard(
                header = {
                    Text(stringResource(R.string.tunnel_named_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (info.mode == TunnelManager.Mode.NAMED) TunnelPill(info.state)
                }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.tunnel_named_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (info.hasSavedToken) {
                            Pill(stringResource(R.string.tunnel_token_saved), StratosColors.SuccessSoft, StratosColors.Success)
                        } else {
                            Pill(stringResource(R.string.tunnel_token_missing))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { tokenDialog = true }) {
                            Text(stringResource(R.string.tunnel_token_manage), color = StratosColors.Accent)
                        }
                    }
                    info.publicUrl?.takeIf { info.mode == TunnelManager.Mode.NAMED }?.let { url ->
                        PublicUrlRow(url)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (info.mode == TunnelManager.Mode.NAMED && info.state != TunnelManager.TunnelState.DISCONNECTED) {
                            Button(onClick = { vm.disconnect() }) { Text(stringResource(R.string.disconnect)) }
                        } else {
                            Button(onClick = { vm.connectNamed() }, enabled = hostingOn && info.hasSavedToken) {
                                Icon(Icons.Filled.Link, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tunnel_named_connect))
                            }
                        }
                    }
                    (info.error)?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- Custom domain ----
            SectionCard(
                header = {
                    Text(stringResource(R.string.domain_card_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (prefs.customDomain.isNotBlank()) {
                        Pill(stringResource(R.string.domain_card_saved), StratosColors.SuccessSoft, StratosColors.Success)
                    } else {
                        Pill(stringResource(R.string.domain_card_none))
                    }
                }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.domain_card_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (prefs.customDomain.isNotBlank()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(StratosColors.SuccessSoft, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(StratosColors.Success)
                            Spacer(Modifier.width(10.dp))
                            MonoText(prefs.customDomain, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF14532D))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { nav.navigate(Routes.DOMAIN_WIZARD) { launchSingleTop = true } }) {
                            Text(stringResource(R.string.domain_card_manage))
                        }
                    }
                }
            }

            // ---- Log tail (redacted) ----
            SectionCard(
                header = { Text(stringResource(R.string.tunnel_logs), style = MaterialTheme.typography.titleSmall) }
            ) {
                Box(
                    Modifier
                        .padding(12.dp)
                        .fillMaxWidth()
                        .stratosGlass(RoundedCornerShape(12.dp), highlight = false)
                        .padding(12.dp)
                ) {
                    if (tail.isEmpty()) {
                        Text(
                            stringResource(R.string.tunnel_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            tail.takeLast(24).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (tokenDialog) {
        TokenDialog(
            hasSaved = info.hasSavedToken,
            onSave = { vm.saveToken(it); tokenDialog = false },
            onClear = { vm.clearToken(); tokenDialog = false },
            onDismiss = { tokenDialog = false }
        )
    }
}

@Composable
private fun TunnelPill(state: TunnelManager.TunnelState) {
    when (state) {
        TunnelManager.TunnelState.CONNECTED -> Pill(stringResource(R.string.tunnel_connected), StratosColors.SuccessSoft, StratosColors.Success)
        TunnelManager.TunnelState.CONNECTING -> Pill(stringResource(R.string.tunnel_connecting), StratosColors.WarningSoft, StratosColors.Warning)
        TunnelManager.TunnelState.ERROR -> Pill(stringResource(R.string.tunnel_error), StratosColors.ErrorSoft, StratosColors.Error)
        else -> Pill(stringResource(R.string.tunnel_off))
    }
}

@Composable
private fun PublicUrlRow(url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .stratosGlass(RoundedCornerShape(14.dp), highlight = false)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(StratosColors.Success)
        Spacer(Modifier.width(10.dp))
        MonoText(url, Modifier.weight(1f))
        IconButton(onClick = {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy), Modifier.size(16.dp))
        }
        IconButton(onClick = {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }) {
            Icon(Icons.Filled.OpenInBrowser, contentDescription = stringResource(R.string.open), Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TokenDialog(
    hasSaved: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tunnel_token_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tunnel_token_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.tunnel_token_field)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasSaved) {
                    Text(
                        stringResource(R.string.tunnel_token_present),
                        style = MaterialTheme.typography.bodySmall,
                        color = StratosColors.Success
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(token) }, enabled = token.length > 20) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (hasSaved) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.tunnel_token_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
