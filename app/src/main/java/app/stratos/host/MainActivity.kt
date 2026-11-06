package app.stratos.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import app.stratos.host.data.Settings
import app.stratos.host.ui.StratosAppUi
import app.stratos.host.ui.theme.StratosTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-activity Compose app. AppCompatActivity (not ComponentActivity) so
 * the AppCompat per-app locale backport works on API 26–32 — the base of the
 * trilingual AR/EN/TR support.
 */
class MainActivity : AppCompatActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply the persisted in-app language (system | en | ar | tr).
        lifecycleScope.launch {
            val lang = StratosApp.instance.graph.settings.settings.first().language
            val desired = if (lang == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
            if (AppCompatDelegate.getApplicationLocales() != desired) {
                AppCompatDelegate.setApplicationLocales(desired)
            }
        }

        requestNotificationPermissionIfNeeded()

        setContent {
            // The persisted theme choice (light | dark | system) now truly
            // drives StratosTheme; default "system" follows the OS.
            val settings by StratosApp.instance.graph.settings.settings
                .collectAsState(initial = Settings())
            val dark = when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            StratosTheme(darkTheme = dark) {
                StratosAppUi()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
