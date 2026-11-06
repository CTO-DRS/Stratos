package app.stratos.host.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.data.SiteEntity
import app.stratos.host.data.SitesRepository
import app.stratos.host.ui.Routes
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.theme.GlowFab
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.util.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SitesViewModel : ViewModel() {
    private val graph = StratosApp.instance.graph

    val sites = graph.repository.observeAll()

    sealed interface ImportState {
        data object Idle : ImportState
        data object Working : ImportState
        data class Preview(val preview: SitesRepository.ImportPreview) : ImportState
        data class Error(val message: String) : ImportState
    }

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: MutableStateFlow<ImportState> = _import

    fun stage(uri: Uri) {
        _import.value = ImportState.Working
        viewModelScope.launch {
            runCatching { graph.repository.stageImport(uri) }
                .onSuccess { _import.value = ImportState.Preview(it) }
                .onFailure { _import.value = ImportState.Error(it.message ?: "import failed") }
        }
    }

    fun confirm(preview: SitesRepository.ImportPreview, name: String, spa: Boolean, lang: String, replaceOf: SiteEntity?) {
        _import.value = ImportState.Working
        viewModelScope.launch {
            runCatching {
                if (replaceOf != null) {
                    graph.repository.replaceContent(replaceOf, preview.stagingFile)
                } else {
                    graph.repository.commitImport(preview, name, spa, lang)
                }
                graph.controller.reloadSites()
            }
                .onSuccess { _import.value = ImportState.Idle }
                .onFailure { _import.value = ImportState.Error(it.message ?: "commit failed") }
        }
    }

    fun cancel(preview: SitesRepository.ImportPreview?) {
        viewModelScope.launch {
            preview?.stagingFile?.delete()
            preview?.stagingDir?.deleteRecursively()
            _import.value = ImportState.Idle
        }
    }

    fun toggle(site: SiteEntity, enabled: Boolean) = viewModelScope.launch {
        graph.repository.setEnabled(site.id, enabled)
        graph.controller.reloadSites()
    }

    fun setDefault(site: SiteEntity) = viewModelScope.launch {
        graph.repository.setDefault(site.id)
        graph.controller.reloadSites()
    }

    fun delete(site: SiteEntity) = viewModelScope.launch {
        graph.repository.delete(site)
        graph.controller.reloadSites()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen(nav: NavHostController, vm: SitesViewModel = viewModel()) {
    val sites by vm.sites.collectAsState(initial = emptyList())
    val importState by vm.import.collectAsState()

    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // persist read permission for the session
            runCatching {
                StratosApp.instance.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.stage(uri)
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            GlowFab(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.import_site),
                onClick = {
                    pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_sites)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (sites.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🗂️", style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.sites_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.sites_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
            items(sites, key = { it.id }) { site ->
                SiteCard(
                    site = site,
                    onToggle = { vm.toggle(site, it) },
                    onSetDefault = { vm.setDefault(site) },
                    onEdit = { nav.navigate(Routes.editor(site.id)) },
                    onDelete = { vm.delete(site) },
                    onReplace = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    when (val st = importState) {
        is SitesViewModel.ImportState.Working -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(stringResource(R.string.importing)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text(stringResource(R.string.import_working))
                    }
                }
            )
        }
        is SitesViewModel.ImportState.Preview -> {
            ImportPreviewDialog(
                preview = st.preview,
                onConfirm = { name, spa, lang ->
                    vm.confirm(st.preview, name, spa, lang, null)
                },
                onCancel = { vm.cancel(st.preview) }
            )
        }
        is SitesViewModel.ImportState.Error -> {
            AlertDialog(
                onDismissRequest = { vm.cancel(null) },
                confirmButton = {
                    TextButton(onClick = { vm.cancel(null) }) { Text(stringResource(R.string.close)) }
                },
                title = { Text(stringResource(R.string.import_rejected)) },
                text = { Text(st.message) }
            )
        }
        else -> {}
    }
}

@Composable
private fun SiteCard(
    site: SiteEntity,
    onToggle: (Boolean) -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReplace: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(site.name, style = MaterialTheme.typography.titleMedium)
                        if (site.isDefault) {
                            Pill(stringResource(R.string.default_badge), StratosColors.AccentSoft, StratosColors.Accent)
                        }
                    }
                    Text(
                        "${Formatters.count(site.filesCount.toLong())} ${stringResource(R.string.files_unit)} · ${Formatters.bytes(site.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = site.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!site.isDefault) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_default)) },
                                onClick = { menu = false; onSetDefault() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_editor)) },
                            leadingIcon = { Icon(Icons.Filled.Code, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.replace_content)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onReplace() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_site), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (site.hasIndex) {
                    Pill(stringResource(R.string.index_ok), StratosColors.SuccessSoft, StratosColors.Success)
                } else {
                    Pill(stringResource(R.string.index_missing), StratosColors.WarningSoft, StratosColors.Warning)
                }
                if (site.spa) Pill("SPA")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit, enabled = site.enabled) {
                    Icon(Icons.Filled.Code, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.edit))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.delete_site_title)) },
            text = { Text(stringResource(R.string.delete_site_body, site.name)) }
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: SitesRepository.ImportPreview,
    onConfirm: (name: String, spa: Boolean, lang: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(preview.suggestedName) }
    var spa by remember { mutableStateOf(true) }
    var lang by remember { mutableStateOf("en") }
    val s = preview.summary

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            Button(onClick = { onConfirm(name.ifBlank { "Site" }, spa, lang) }) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.import_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        R.string.import_summary,
                        s.files, Formatters.bytes(s.bytes)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.hasIndex) {
                        Pill(stringResource(R.string.index_ok), StratosColors.SuccessSoft, StratosColors.Success)
                    } else {
                        Pill(stringResource(R.string.index_missing), StratosColors.WarningSoft, StratosColors.Warning)
                    }
                    Pill(stringResource(R.string.import_root, s.root))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.site_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = spa, onCheckedChange = { spa = it })
                    Text(stringResource(R.string.spa_mode), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )
}
