package app.stratos.host.tunnel

import android.content.Context
import app.stratos.host.data.SettingsStore
import app.stratos.host.security.Vault
import app.stratos.host.util.Redact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the bundled `cloudflared` binary:
 *  - Quick Tunnel: temporary https://*.trycloudflare.com URL, no account.
 *  - Named Tunnel: persistent hostname from a token (encrypted at rest in the
 *    Keystore vault, passed to the process via environment only — never argv,
 *    never logs, never UI echo).
 */
class TunnelManager(
    private val context: Context,
    private val vault: Vault,
    private val settings: SettingsStore
) {

    enum class Mode { QUICK, NAMED }
    enum class TunnelState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class TunnelInfo(
        val state: TunnelState = TunnelState.DISCONNECTED,
        val mode: Mode? = null,
        val publicUrl: String? = null,
        val error: String? = null,
        val hasSavedToken: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = ArrayDeque<String>(MAX_LOGS)

    private val _info = MutableStateFlow(TunnelInfo())
    val info: StateFlow<TunnelInfo> = _info.asStateFlow()

    private var process: Process? = null
    private var currentMode: Mode? = null

    init {
        scope.launch { refreshSavedTokenFlag() }
    }

    // ---- token management ----------------------------------------------------

    fun saveToken(token: CharArray) {
        vault.put(KEY_TUNNEL_TOKEN, token)
        scope.launch { refreshSavedTokenFlag() }
    }

    fun clearToken() {
        vault.remove(KEY_TUNNEL_TOKEN)
        scope.launch { refreshSavedTokenFlag() }
    }

    fun hasToken(): Boolean = vault.has(KEY_TUNNEL_TOKEN)

    private suspend fun refreshSavedTokenFlag() {
        _info.value = _info.value.copy(hasSavedToken = hasToken())
    }

    // ---- lifecycle -----------------------------------------------------------

    fun binary(): File = File(context.filesDir, "native/cloudflared")

    fun isBinaryPresent(): Boolean = binary().let { it.exists() && it.canExecute() }

    suspend fun connect(mode: Mode, originPort: Int): Result<String> = withContext(Dispatchers.IO) {
        if (process != null) {
            return@withContext Result.failure(IllegalStateException("tunnel already running"))
        }
        if (!isBinaryPresent()) {
            return@withContext Result.failure(IllegalStateException(
                "cloudflared binary missing: place it at filesDir/native/cloudflared (see README)"
            ))
        }
        currentMode = mode
        _info.value = TunnelInfo(state = TunnelState.CONNECTING, mode = mode, hasSavedToken = hasToken())
        clearLogs()

        runCatching {
            val (argv, envToken) = when (mode) {
                Mode.QUICK -> listOf(
                    binary().absolutePath, "tunnel", "--url", "http://127.0.0.1:$originPort",
                    "--no-autoupdate", "--protocol", "http2"
                ) to null
                Mode.NAMED -> {
                    val token = vault.get(KEY_TUNNEL_TOKEN)?.concatToString()
                        ?: error("no saved tunnel token")
                    listOf(
                        binary().absolutePath, "tunnel", "run",
                        "--protocol", "http2", "--no-autoupdate"
                    ) to token
                }
            }

            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            envToken?.let { pb.environment()["TUNNEL_TOKEN"] = it }
            pb.environment()["CLOUDFLARED_NO_UPDATE"] = "1"

            val proc = pb.start()
            process = proc

            scope.launch { consumeLogs(proc) }
            "started"
        }.recoverCatching { t ->
            _info.value = TunnelInfo(
                state = TunnelState.ERROR, mode = mode,
                error = t.message ?: "spawn failed", hasSavedToken = hasToken()
            )
            throw t
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { process?.destroy() }
        process = null
        currentMode = null
        _info.value = TunnelInfo(state = TunnelState.DISCONNECTED, hasSavedToken = hasToken())
        appendLog("tunnel stopped")
    }

    fun tailLogs(n: Int = 100): List<String> = synchronized(logs) {
        logs.takeLast(n)
    }

    fun clearLogs() = synchronized(logs) { logs.clear() }

    // ---- internals -----------------------------------------------------------

    private val urlRegex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")

    private suspend fun consumeLogs(proc: Process) {
        proc.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = Redact.apply(raw)
                appendLog(line)
                urlRegex.find(line)?.let {
                    _info.value = _info.value.copy(
                        state = TunnelState.CONNECTED,
                        publicUrl = it.value
                    )
                }
                if (line.contains("Registered tunnel connection", ignoreCase = true) &&
                    currentMode == Mode.NAMED
                ) {
                    _info.value = _info.value.copy(state = TunnelState.CONNECTED)
                }
                if (line.contains("failed", ignoreCase = true) &&
                    line.contains("Unable to reach", ignoreCase = true)
                ) {
                    _info.value = _info.value.copy(
                        state = TunnelState.ERROR,
                        error = "origin unreachable — is hosting running?"
                    )
                }
            }
        }
        val code = proc.waitFor()
        if (_info.value.state != TunnelState.DISCONNECTED) {
            _info.value = _info.value.copy(
                state = TunnelState.ERROR,
                error = "cloudflared exited with code $code"
            )
        }
    }

    private fun appendLog(line: String) = synchronized(logs) {
        if (logs.size >= MAX_LOGS) logs.removeFirst()
        logs.addLast(line)
    }

    private companion object {
        const val KEY_TUNNEL_TOKEN = "tunnel_token"
        const val MAX_LOGS = 200
    }
}
