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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.data.Settings
import app.stratos.host.tunnel.CustomDomain
import app.stratos.host.tunnel.TunnelToken
import app.stratos.host.ui.components.MonoText
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.theme.StratosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Guided custom-domain wizard (5 steps):
 *  1. hostname input + RFC 1123 validation
 *  2. paste the Cloudflare tunnel token (only the PUBLIC tunnel ID is decoded;
 *     the secret is never extracted, shown or stored by the wizard)
 *  3. review the decoded tunnel ID and CNAME target
 *  4. DNS record instructions (CNAME -> <tunnel-id>.cfargotunnel.com, proxied)
 *  5. live HTTPS verification of the published site, then persist the domain.
 */
class DomainWizardViewModel : ViewModel() {

    private val graph = StratosApp.instance.graph

    val settings = graph.settings.settings
    val verify = MutableStateFlow<VerifyState>(VerifyState.Idle)

    sealed interface VerifyState {
        data object Idle : VerifyState
        data object Checking : VerifyState
        data class Ok(val url: String, val code: Int) : VerifyState
        data class Fail(val reason: String) : VerifyState
    }

    /** Plain HTTPS check of the hostname. Runs off the main thread, never throws. */
    fun check(domain: String) {
        if (verify.value is VerifyState.Checking) return
        verify.value = VerifyState.Checking
        viewModelScope.launch {
            verify.value = withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(12, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .build()
                    val call = client.newCall(
                        Request.Builder()
                            .url("https://$domain")
                            .header("User-Agent", "Stratos-DomainWizard/0.1")
                            .build()
                    )
                    call.execute().use { resp ->
                        if (resp.isSuccessful) VerifyState.Ok(resp.request.url.toString(), resp.code)
                        else VerifyState.Fail("HTTP ${resp.code}")
                    }
                }.getOrElse { VerifyState.Fail(it.message ?: "network error") }
            }
        }
    }

    fun save(domain: String) = viewModelScope.launch { graph.settings.setCustomDomain(domain) }
    fun clear() = viewModelScope.launch { graph.settings.setCustomDomain("") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainWizardScreen(nav: NavHostController, vm: DomainWizardViewModel = viewModel()) {
    val settings by vm.settings.collectAsState(initial = Settings())
    val verifyState by vm.verify.collectAsState()
    val context = LocalContext.current

    var step by remember { mutableIntStateOf(0) }
    var domainInput by remember { mutableStateOf(settings.customDomain) }
    var tokenInput by remember { mutableStateOf("") }
    var tunnelId by remember { mutableStateOf<String?>(null) }
    var domainError by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val domain = CustomDomain.normalize(domainInput)
    val domainValid = CustomDomain.isValid(domain)
    val cnameTarget = tunnelId?.plus(".cfargotunnel.com")

    // Persist automatically once verification succeeds.
    LaunchedEffect(verifyState) {
        if (verifyState is DomainWizardViewModel.VerifyState.Ok && !saved) {
            saved = true
            vm.save(domain)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.domain_wizard_title)) },
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
            LinearProgressIndicator(
                progress = { (step + 1) / 5f },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                stringResource(R.string.domain_step_of, step + 1, 5),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (step) {
                0 -> {
                    WizardCard(title = stringResource(R.string.domain_s1_title)) {
                        Text(
                            stringResource(R.string.domain_s1_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (settings.customDomain.isNotBlank()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(StratosColors.SuccessSoft, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MonoText(settings.customDomain, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF14532D))
                                TextButton(onClick = { vm.clear() }) {
                                    Text(stringResource(R.string.tunnel_token_clear), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = domainInput,
                            onValueChange = {
                                domainInput = it
                                domainError = false
                            },
                            label = { Text(stringResource(R.string.domain_field)) },
                            singleLine = true,
                            isError = domainError,
                            supportingText = {
                                if (domainError) Text(stringResource(R.string.domain_invalid))
                                else if (domainValid && CustomDomain.isBareDomain(domain)) Text(stringResource(R.string.domain_bare_hint))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        WizardNavRow(
                            primary = stringResource(R.string.domain_next),
                            primaryEnabled = domainValid,
                            onPrimary = { step = 1 }
                        )
                    }
                }

                1 -> {
                    WizardCard(title = stringResource(R.string.domain_s2_title)) {
                        Text(
                            stringResource(R.string.domain_token_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                tokenError = false
                            },
                            label = { Text(stringResource(R.string.tunnel_token_field)) },
                            singleLine = true,
                            isError = tokenError,
                            supportingText = { if (tokenError) Text(stringResource(R.string.domain_token_invalid)) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        WizardNavRow(
                            primary = stringResource(R.string.domain_next),
                            primaryEnabled = tokenInput.isNotBlank(),
                            onPrimary = {
                                val id = TunnelToken.decodeTunnelId(tokenInput)
                                if (id == null) tokenError = true
                                else {
                                    tunnelId = id
                                    step = 2
                                }
                            },
                            secondary = stringResource(R.string.back),
                            onSecondary = { step = 0 }
                        )
                    }
                }

                2 -> {
                    WizardCard(title = stringResource(R.string.domain_s3_title)) {
                        Text(
                            stringResource(R.string.domain_s3_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MonoRow(label = stringResource(R.string.domain_field), value = domain)
                        MonoRow(label = stringResource(R.string.domain_tunnel_id), value = tunnelId.orEmpty())
                        MonoRow(
                            label = stringResource(R.string.domain_cname_target),
                            value = cnameTarget.orEmpty(),
                            onCopy = {
                                copyToClipboard(context, cnameTarget.orEmpty())
                            }
                        )
                        WizardNavRow(
                            primary = stringResource(R.string.domain_next),
                            primaryEnabled = true,
                            onPrimary = { step = 3 },
                            secondary = stringResource(R.string.back),
                            onSecondary = { step = 1 }
                        )
                    }
                }

                3 -> {
                    WizardCard(title = stringResource(R.string.domain_s4_title)) {
                        DnsStepRow(stringResource(R.string.domain_dns_type))
                        DnsStepRow(stringResource(R.string.domain_dns_name, domain))
                        MonoRow(
                            label = stringResource(R.string.domain_cname_target),
                            value = cnameTarget.orEmpty(),
                            onCopy = { copyToClipboard(context, cnameTarget.orEmpty()) }
                        )
                        DnsStepRow(stringResource(R.string.domain_dns_proxy))
                        Text(
                            stringResource(R.string.domain_dns_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = StratosColors.Warning
                        )
                        WizardNavRow(
                            primary = stringResource(R.string.domain_next),
                            primaryEnabled = true,
                            onPrimary = { step = 4 },
                            secondary = stringResource(R.string.back),
                            onSecondary = { step = 2 }
                        )
                    }
                }

                4 -> {
                    WizardCard(title = stringResource(R.string.domain_s5_title)) {
                        Text(
                            stringResource(R.string.domain_verify_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (val v = verifyState) {
                            is DomainWizardViewModel.VerifyState.Checking -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.domain_verify_checking), style = MaterialTheme.typography.bodySmall)
                            }

                            is DomainWizardViewModel.VerifyState.Ok -> {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(StratosColors.SuccessSoft, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = StratosColors.Success)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            stringResource(R.string.domain_verify_ok),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = androidx.compose.ui.graphics.Color(0xFF14532D)
                                        )
                                        MonoText(v.url, color = androidx.compose.ui.graphics.Color(0xFF14532D))
                                        Text(
                                            stringResource(R.string.domain_saved),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF14532D)
                                        )
                                    }
                                }
                            }

                            is DomainWizardViewModel.VerifyState.Fail -> {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(StratosColors.ErrorSoft, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp), tint = StratosColors.Error)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            stringResource(R.string.domain_verify_fail) + " — " + v.reason,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            stringResource(R.string.domain_verify_fail_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            else -> Unit
                        }
                        WizardNavRow(
                            primary = stringResource(R.string.domain_verify_btn),
                            primaryEnabled = verifyState !is DomainWizardViewModel.VerifyState.Checking,
                            onPrimary = { vm.check(domain) },
                            secondary = stringResource(R.string.back),
                            onSecondary = { step = 3 }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (verifyState is DomainWizardViewModel.VerifyState.Ok) {
                                Button(onClick = { nav.popBackStack() }) {
                                    Text(stringResource(R.string.domain_done))
                                }
                            } else {
                                OutlinedButton(onClick = {
                                    vm.save(domain)
                                    nav.popBackStack()
                                }) {
                                    Text(stringResource(R.string.domain_save_anyway))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WizardCard(title: String, content: @Composable () -> Unit) {
    SectionCard(
        header = { Text(title, style = MaterialTheme.typography.titleMedium) }
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
private fun WizardNavRow(
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (secondary != null && onSecondary != null) {
            OutlinedButton(onClick = onSecondary) { Text(secondary) }
        }
        Button(onClick = onPrimary, enabled = primaryEnabled) { Text(primary) }
    }
}

/** Label + monospace value row with an optional copy action. */
@Composable
private fun MonoRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(value, Modifier.weight(1f))
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
private fun DnsStepRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("DNS", StratosColors.AccentSoft, StratosColors.Accent)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("cname", text))
}
