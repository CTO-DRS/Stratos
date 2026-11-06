package app.stratos.host.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.ai.AiClient
import app.stratos.host.ai.PromptBuilder
import app.stratos.host.ai.TemplateGenerator
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.clickableNoRipple
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.theme.StratosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AiViewModel : ViewModel() {

    enum class EngineMode { OFFLINE_TEMPLATE, AI_API }

    sealed interface GenState {
        data object Idle : GenState
        data object Working : GenState
        data class Done(val html: String) : GenState
        data class Error(val message: String) : GenState
    }

    private val graph = StratosApp.instance.graph

    val settings = graph.settings.settings
    private val _state = MutableStateFlow<GenState>(GenState.Idle)
    val state: MutableStateFlow<GenState> = _state

    private var lastInput: TemplateGenerator.Input? = null

    fun generate(
        mode: EngineMode,
        title: String,
        tagline: String,
        about: String,
        services: List<String>,
        phone: String, email: String, address: String,
        language: String, accent: String,
        sections: List<String>
    ) {
        _state.value = GenState.Working
        viewModelScope.launch {
            val input = TemplateGenerator.Input(
                title = title.ifBlank { "My Site" },
                tagline = tagline,
                about = about,
                services = services,
                phone = phone, email = email, address = address,
                language = language, accent = accent
            )
            lastInput = input
            when (mode) {
                EngineMode.OFFLINE_TEMPLATE -> {
                    val html = TemplateGenerator.generate(input)
                    _state.value = GenState.Done(html)
                }
                EngineMode.AI_API -> withContext(Dispatchers.IO) {
                    val s = settings.first()
                    val endpoint = s.aiEndpoint.ifBlank { "https://open.bigmodel.cn/api/paas/v4" }
                    val key = graph.vault.get("ai_api_key")
                    if (key == null || key.isEmpty()) {
                        _state.value = GenState.Error("no AI API key saved — add one in Settings")
                        return@withContext
                    }
                    val (system, user) = PromptBuilder.forLanguage(
                        PromptBuilder.Request(title, about.ifBlank { tagline }, language, accentName(accent), sections)
                    )
                    AiClient().complete(endpoint, key, s.aiModel, system, user)
                        .onSuccess { _state.value = GenState.Done(it) }
                        .onFailure { _state.value = GenState.Error(it.message ?: "AI request failed") }
                }
            }
        }
    }

    private fun accentName(hex: String): String = when (hex) {
        "#0E9F6E" -> "emerald"
        "#7C3AED" -> "violet"
        "#0A0A0A" -> "mono"
        else -> "blue"
    }

    /** Saves the generated HTML as a real hosted site. */
    fun saveAsSite(name: String, language: String, spa: Boolean) {
        val html = (_state.value as? GenState.Done)?.html ?: return
        _state.value = GenState.Working
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val id = SitesRepoHelper.randomId()
                val dir = File(StratosApp.instance.filesDir, "sites/$id")
                dir.mkdirs()
                File(dir, "index.html").writeText(html)
                graph.repository.commitGeneratedSite(
                    id = id, name = name, extractDir = dir,
                    hasIndex = true, sizeBytes = html.toByteArray().size.toLong(),
                    spa = spa, language = language
                )
                graph.controller.reloadSites()
            }
                .onSuccess { _state.value = GenState.Idle }
                .onFailure { _state.value = GenState.Error(it.message ?: "save failed") }
        }
    }

    fun reset() { _state.value = GenState.Idle }
}

/** Small indirection so the VM does not depend on repository internals. */
object SitesRepoHelper {
    fun randomId(): String = app.stratos.host.data.SitesRepository.randomId()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiBuilderScreen(nav: NavHostController, vm: AiViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState(initial = app.stratos.host.data.Settings())

    var mode by remember { mutableStateOf(AiViewModel.EngineMode.OFFLINE_TEMPLATE) }
    var title by remember { mutableStateOf("") }
    var tagline by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var services by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("ar") }
    var accent by remember { mutableStateOf("#0A7CFF") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_ai)) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.ai_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Engine mode chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == AiViewModel.EngineMode.OFFLINE_TEMPLATE,
                            onClick = { mode = AiViewModel.EngineMode.OFFLINE_TEMPLATE },
                            label = { Text(stringResource(R.string.ai_mode_offline)) }
                        )
                        FilterChip(
                            selected = mode == AiViewModel.EngineMode.AI_API,
                            onClick = { mode = AiViewModel.EngineMode.AI_API },
                            label = { Text(stringResource(R.string.ai_mode_api)) }
                        )
                    }
                    if (mode == AiViewModel.EngineMode.AI_API) {
                        Text(
                            if (settings.aiEndpoint.isBlank()) stringResource(R.string.ai_key_needed_settings)
                            else stringResource(R.string.ai_endpoint_set, settings.aiEndpoint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.aiEndpoint.isBlank()) StratosColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.ai_site_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tagline, { tagline = it }, label = { Text(stringResource(R.string.ai_tagline)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(about, { about = it }, label = { Text(stringResource(R.string.ai_about)) }, minLines = 3, modifier = Modifier.fillMaxWidth())

                    if (mode == AiViewModel.EngineMode.OFFLINE_TEMPLATE) {
                        OutlinedTextField(services, { services = it }, label = { Text(stringResource(R.string.ai_services_hint)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.ai_phone)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.ai_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.ai_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }

                    // Language
                    Text(stringResource(R.string.ai_language), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ar" to stringResource(R.string.lang_ar), "en" to stringResource(R.string.lang_en), "tr" to stringResource(R.string.lang_tr)).forEach { (code, label) ->
                            FilterChip(selected = language == code, onClick = { language = code }, label = { Text(label) })
                        }
                    }

                    // Palette
                    Text(stringResource(R.string.ai_palette), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("#0A7CFF" to stringResource(R.string.palette_blue), "#0E9F6E" to stringResource(R.string.palette_emerald), "#7C3AED" to stringResource(R.string.palette_violet), "#0A0A0A" to stringResource(R.string.palette_mono)).forEach { (hex, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .background(
                                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex)),
                                            RoundedCornerShape(5.dp)
                                        )
                                        .border(
                                            if (accent == hex) 2.dp else 1.dp,
                                            if (accent == hex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(5.dp)
                                        )
                                        .clickableNoRipple { accent = hex }
                                )
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            vm.generate(
                                mode, title, tagline, about,
                                services.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() },
                                phone, email, address,
                                language, accent,
                                listOf("hero", "about", "services", "contact")
                            )
                        },
                        enabled = state != AiViewModel.GenState.Working && title.isNotBlank() && (mode == AiViewModel.EngineMode.OFFLINE_TEMPLATE || settings.aiEndpoint.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state == AiViewModel.GenState.Working) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.ai_generate))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    when (val st = state) {
        is AiViewModel.GenState.Done -> {
            GeneratedDialog(
                html = st.html,
                language = language,
                onSave = { name, spa -> vm.saveAsSite(name, language, spa) },
                onClose = { vm.reset() }
            )
        }
        is AiViewModel.GenState.Error -> {
            AlertDialog(
                onDismissRequest = { vm.reset() },
                confirmButton = { TextButton(onClick = { vm.reset() }) { Text(stringResource(R.string.close)) } },
                title = { Text(stringResource(R.string.ai_failed)) },
                text = { Text(st.message) }
            )
        }
        else -> {}
    }
}

@Composable
private fun GeneratedDialog(
    html: String,
    language: String,
    onSave: (String, Boolean) -> Unit,
    onClose: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var spa by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(onClick = { onSave(name.ifBlank { "AI Site" }, spa) }) {
                Text(stringResource(R.string.ai_save_site))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.ai_generated_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { /* preview handled below via WebView */ }, modifier = Modifier.fillMaxWidth(), enabled = false) {
                    Text(stringResource(R.string.ai_generated_size, html.toByteArray().size / 1024))
                }
                HtmlPreview(html)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.site_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = spa, onCheckedChange = { spa = it })
                    Text(stringResource(R.string.spa_mode), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )
}

@Composable
private fun HtmlPreview(html: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
    ) {
        val web = remember(html) {
            android.webkit.WebView(context).apply {
                settings.javaScriptEnabled = false
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
        androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxSize(), factory = { web })
    }
}
