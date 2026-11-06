package app.stratos.host.host

import app.stratos.host.data.SettingsStore
import app.stratos.host.data.SitesRepository
import app.stratos.host.engine.EngineApi
import app.stratos.host.engine.EngineKind
import app.stratos.host.engine.EngineStatus
import app.stratos.host.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** High-level hosting state surfaced to the UI and the notification. */
sealed interface HostState {
    data object Idle : HostState
    data object Starting : HostState
    data class Running(val lanUrl: String, val loopbackUrl: String, val engineKind: EngineKind) : HostState
    data object Stopping : HostState
    data class Failed(val reason: String) : HostState
}

/**
 * Orchestrates start/stop of the embedded engine with the site registry from
 * the repository. One instance per process (owned by AppGraph); the foreground
 * service and the UI both talk to it, so state stays consistent everywhere.
 */
class HostingController(
    private val engine: EngineApi,
    private val repository: SitesRepository,
    private val settings: SettingsStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _status = MutableStateFlow<EngineStatus?>(null)
    val status: StateFlow<EngineStatus?> = _status.asStateFlow()

    private var pollJob: kotlinx.coroutines.Job? = null

    suspend fun start(): Result<HostState> = withContext(Dispatchers.IO) {
        if (_state.value is HostState.Running) return@withContext Result.success(_state.value)
        _state.value = HostState.Starting
        runCatching {
            val s = settings.settings.first()
            val sites = repository.engineSites()
            if (sites.isEmpty()) error("no enabled site to host — import one first")

            val bind = if (s.bindAll) "0.0.0.0" else "127.0.0.1"
            val port = if (s.port in 1024..65535) s.port else 8080

            engine.start(bind, port, sites).getOrThrow()

            val lanIp = NetUtils.lanIp()
            HostState.Running(
                lanUrl = "http://${lanIp ?: "<lan-ip>"}:$port/",
                loopbackUrl = "http://127.0.0.1:$port/",
                engineKind = engine.kind
            ).also {
                _state.value = it
                startPolling()
            }
        }.onFailure {
            _state.value = HostState.Failed(it.message ?: "unknown error")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        _state.value = HostState.Stopping
        runCatching { engine.stop() }
        pollJob?.cancel()
        pollJob = null
        _status.value = null
        _state.value = HostState.Idle
    }

    suspend fun reloadSites() {
        if (_state.value is HostState.Running) {
            engine.setSites(repository.engineSites())
        }
    }

    suspend fun resetMetrics() = engine.resetMetrics()

    /** Live metrics stream sampled once per second while the collector lives. */
    fun metricsFlow(periodMs: Long = 1000) = kotlinx.coroutines.flow.flow {
        while (currentCoroutineContext().isActive) {
            _status.value = engine.status()
            emit(_status.value)
            delay(periodMs)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                _status.value = engine.status()
                delay(1000)
            }
        }
    }
}
