package app.stratos.host

import android.app.Application
import android.net.Uri
import app.stratos.host.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stratos — your pocket server.
 *
 * Application entry point: owns the dependency graph so every layer shares a
 * single DB, engine, vault and controller instance.
 */
class StratosApp : Application() {

    lateinit var graph: AppGraph
        private set

    /**
     * ZIPs handed over from outside the app (share-target / open-with).
     * MainActivity stages each stream into the cache, then offers the local
     * file here; SitesScreen consumes the queue into its import flow.
     */
    private val _pendingImports = MutableStateFlow<List<Uri>>(emptyList())
    val pendingImports: StateFlow<List<Uri>> = _pendingImports.asStateFlow()

    fun offerImport(uri: Uri) {
        _pendingImports.value = _pendingImports.value + uri
    }

    fun consumeImport(uri: Uri) {
        _pendingImports.value = _pendingImports.value - uri
    }

    companion object {
        lateinit var instance: StratosApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
    }
}
