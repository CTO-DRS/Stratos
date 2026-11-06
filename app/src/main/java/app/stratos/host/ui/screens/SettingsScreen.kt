package app.stratos.host.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.data.Settings
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.util.NetUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val graph = StratosApp.instance.graph

    private val _settings = MutableStateFlow(Settings())
    val settings: MutableStateFlow<Settings> = _settings

    val engineKind = graph.engine.kind
    val engineVersion = graph.engine.version
    val cloudflaredPresent = graph.tunnel.isBinaryPresent()
    val lanIp = NetUtils.lanIp() ?: "—"
    val aiKeySaved = StratosApp.instance.graph.vault.has("ai_api_key")

    init {
        viewModelScope.launch {
            _settings.value = graph.settings.settings.first()
        }
    }

    fun save(transform: suspend (app.stratos.host.data.SettingsStore) -> Unit) = viewModelScope.launch {
        transform(graph.settings)
        _settings.value = graph.settings.settings.first()
    }

    fun savePort(port: Int) = save { it.setPort(port) }
    fun saveBindAll(v: Boolean) = save { it.setBindAll(v) }
    fun saveSpa(v: Boolean) = save { it.setSpaDefault(v) }
    fun saveTheme(v: String) = save { it.setTheme(v) }
    fun saveLanguage(v: String) = save { it.setLanguage(v) }
    fun saveAiEndpoint(v: String) = save { it.setAiEndpoint(v) }
    fun saveAiModel(v: String) = save { it.setAiModel(v) }

    fun saveAiKey(key: String) = viewModelScope.launch {
        if (key.isBlank()) graph.vault.remove("ai_api_key")
        else graph.vault.put("ai_api_key", key.toCharArray())
    }

    fun replayIntro() = save { it.setOnboardingDone(false) }

    fun reloadIfRunning() = viewModelScope.launch {
        graph.controller.reloadSites()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var aiEndpoint by remember(settings.aiEndpoint) { mutableStateOf(settings.aiEndpoint) }
    var aiModel by remember(settings.aiModel) { mutableStateOf(settings.aiModel) }
    var keyDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Server ----
            SectionCard(
                header = { Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleMedium) }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.settings_port)) },
                            singleLine = true,
                            modifier = Modifier.width(140.dp)
                        )
                        Button(onClick = {
                            val p = portText.toIntOrNull()
                            if (p != null && p in 1024..65535) {
                                vm.savePort(p)
                                vm.reloadIfRunning()
                            } else {
                                portText = settings.port.toString()
                            }
                        }) { Text(stringResource(R.string.apply)) }
                    }
                    Text(
                        stringResource(R.string.settings_port_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(stringResource(R.string.settings_bind), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.bindAll,
                            onClick = { vm.saveBindAll(true); vm.reloadIfRunning() },
                            label = { Text(stringResource(R.string.settings_bind_lan)) }
                        )
                        FilterChip(
                            selected = !settings.bindAll,
                            onClick = { vm.saveBindAll(false); vm.reloadIfRunning() },
                            label = { Text(stringResource(R.string.settings_bind_loopback)) }
                        )
                    }

                    Text(stringResource(R.string.settings_spa), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.spaDefault,
                            onClick = { vm.saveSpa(true); vm.reloadIfRunning() },
                            label = { Text(stringResource(R.string.on)) }
                        )
                        FilterChip(
                            selected = !settings.spaDefault,
                            onClick = { vm.saveSpa(false); vm.reloadIfRunning() },
                            label = { Text(stringResource(R.string.off)) }
                        )
                    }
                }
            }

            // ---- Appearance ----
            SectionCard(
                header = { Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium) }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.language == "system",
                            onClick = { vm.saveLanguage("system"); applyLocale(context, "system") },
                            label = { Text(stringResource(R.string.lang_system)) }
                        )
                        FilterChip(
                            selected = settings.language == "ar",
                            onClick = { vm.saveLanguage("ar"); applyLocale(context, "ar") },
                            label = { Text(stringResource(R.string.lang_ar)) }
                        )
                        FilterChip(
                            selected = settings.language == "en",
                            onClick = { vm.saveLanguage("en"); applyLocale(context, "en") },
                            label = { Text(stringResource(R.string.lang_en)) }
                        )
                        FilterChip(
                            selected = settings.language == "tr",
                            onClick = { vm.saveLanguage("tr"); applyLocale(context, "tr") },
                            label = { Text(stringResource(R.string.lang_tr)) }
                        )
                    }
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.theme == "light",
                            onClick = { vm.saveTheme("light") },
                            label = { Text(stringResource(R.string.theme_light)) }
                        )
                        FilterChip(
                            selected = settings.theme == "dark",
                            onClick = { vm.saveTheme("dark") },
                            label = { Text(stringResource(R.string.theme_dark)) }
                        )
                        FilterChip(
                            selected = settings.theme == "system",
                            onClick = { vm.saveTheme("system") },
                            label = { Text(stringResource(R.string.theme_system)) }
                        )
                    }
                }
            }

            // ---- AI provider ----
            SectionCard(
                header = {
                    Text(stringResource(R.string.settings_ai), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (vm.aiKeySaved) {
                        Pill(stringResource(R.string.ai_key_saved), StratosColors.SuccessSoft, StratosColors.Success)
                    }
                }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        aiEndpoint, { aiEndpoint = it },
                        label = { Text(stringResource(R.string.settings_ai_endpoint)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_ai_endpoint_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        aiModel, { aiModel = it },
                        label = { Text(stringResource(R.string.settings_ai_model)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            vm.saveAiEndpoint(aiEndpoint.trim())
                            vm.saveAiModel(aiModel.trim())
                        }) { Text(stringResource(R.string.save)) }
                        OutlinedButton(onClick = { keyDialog = true }) {
                            Text(
                                if (vm.aiKeySaved) stringResource(R.string.settings_ai_key_change)
                                else stringResource(R.string.settings_ai_key_add)
                            )
                        }
                    }
                }
            }

            // ---- Diagnostics ----
            SectionCard(
                header = { Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium) }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagRow(stringResource(R.string.diag_engine), vm.engineKind.id.uppercase() + " · v" + vm.engineVersion.removePrefix("go").ifBlank { "1.0.0" })
                    DiagRow(stringResource(R.string.diag_lan), vm.lanIp)
                    DiagRow(
                        stringResource(R.string.diag_cloudflared),
                        if (vm.cloudflaredPresent) stringResource(R.string.diag_found) else stringResource(R.string.diag_missing)
                    )
                    Text(
                        stringResource(R.string.diag_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { vm.replayIntro() }) {
                        Text(stringResource(R.string.settings_replay_intro))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (keyDialog) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text(stringResource(R.string.settings_ai_key_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_ai_key_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        key, { key = it },
                        label = { Text(stringResource(R.string.settings_ai_key_field)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveAiKey(key); keyDialog = false }, enabled = key.length > 8) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row {
                    if (vm.aiKeySaved) {
                        TextButton(onClick = { vm.saveAiKey(""); keyDialog = false }) {
                            Text(stringResource(R.string.tunnel_token_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { keyDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.background(androidx.compose.ui.graphics.Color.Transparent))
    }
}

private fun applyLocale(context: android.content.Context, tag: String) {
    val locales = if (tag == "system")
        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
    else
        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
}
