package app.stratos.host.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "stratos_settings")

/** Persisted app settings (never secrets — those live in the Vault). */
data class Settings(
    val port: Int = 8080,
    val bindAll: Boolean = true,          // false → loopback only
    val spaDefault: Boolean = true,
    val theme: String = "light",          // light | dark | system
    val language: String = "system",      // system | en | ar | tr
    val aiEndpoint: String = "",
    val aiModel: String = "glm-4-flash",
    val aiAutoTunnel: Boolean = false,
    val customDomain: String = ""      // empty = none configured
)

class SettingsStore(private val context: Context) {

    private object K {
        val PORT = intPreferencesKey("port")
        val BIND_ALL = booleanPreferencesKey("bind_all")
        val SPA = booleanPreferencesKey("spa_default")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_TUNNEL = booleanPreferencesKey("ai_auto_tunnel")
        val CUSTOM_DOMAIN = stringPreferencesKey("custom_domain")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            port = p[K.PORT] ?: 8080,
            bindAll = p[K.BIND_ALL] ?: true,
            spaDefault = p[K.SPA] ?: true,
            theme = p[K.THEME] ?: "light",
            language = p[K.LANGUAGE] ?: "system",
            aiEndpoint = p[K.AI_ENDPOINT] ?: "",
            aiModel = p[K.AI_MODEL] ?: "glm-4-flash",
            aiAutoTunnel = p[K.AI_TUNNEL] ?: false,
            customDomain = p[K.CUSTOM_DOMAIN] ?: ""
        )
    }

    suspend fun setPort(v: Int) = context.dataStore.edit { it[K.PORT] = v }
    suspend fun setBindAll(v: Boolean) = context.dataStore.edit { it[K.BIND_ALL] = v }
    suspend fun setSpaDefault(v: Boolean) = context.dataStore.edit { it[K.SPA] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.THEME] = v }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[K.LANGUAGE] = v }
    suspend fun setAiEndpoint(v: String) = context.dataStore.edit { it[K.AI_ENDPOINT] = v }
    suspend fun setAiModel(v: String) = context.dataStore.edit { it[K.AI_MODEL] = v }
    suspend fun setAiAutoTunnel(v: Boolean) = context.dataStore.edit { it[K.AI_TUNNEL] = v }
    suspend fun setCustomDomain(v: String) = context.dataStore.edit { it[K.CUSTOM_DOMAIN] = v.trim() }
}
