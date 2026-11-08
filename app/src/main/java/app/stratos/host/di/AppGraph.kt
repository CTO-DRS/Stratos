package app.stratos.host.di

import android.content.Context
import androidx.room.Room
import app.stratos.host.data.AppDb
import app.stratos.host.data.BackupManager
import app.stratos.host.data.SettingsStore
import app.stratos.host.data.SitesRepository
import app.stratos.host.engine.EngineApi
import app.stratos.host.engine.EngineFactory
import app.stratos.host.host.HostingController
import app.stratos.host.security.Vault
import app.stratos.host.tunnel.TunnelManager

/**
 * Hand-rolled dependency graph — no framework, no reflection, nothing to
 * attack. Everything is lazily created exactly once.
 */
class AppGraph(private val context: Context) {

    val database by lazy {
        Room.databaseBuilder(context, AppDb::class.java, "stratos.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val siteDao by lazy { database.siteDao() }

    val vault by lazy { Vault(context) }

    val settings by lazy { SettingsStore(context) }

    val engine: EngineApi by lazy { EngineFactory.create() }

    val repository by lazy { SitesRepository(context, siteDao, engine) }

    val controller by lazy { HostingController(engine, repository, settings, vault) }

    val tunnel by lazy { TunnelManager(context, vault, settings) }

    val backup by lazy { BackupManager(context, siteDao, repository, settings, engine) }
}
