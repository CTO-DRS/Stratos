package app.stratos.host

import android.app.Application
import app.stratos.host.di.AppGraph

/**
 * Stratos — your pocket server.
 *
 * Application entry point: owns the dependency graph so every layer shares a
 * single DB, engine, vault and controller instance.
 */
class StratosApp : Application() {

    lateinit var graph: AppGraph
        private set

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
