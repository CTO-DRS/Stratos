package app.stratos.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-activity Compose app. AppCompatActivity (not ComponentActivity) so
 * the AppCompat per-app locale backport works on API 26–32 — the base of the
 * trilingual AR/EN/TR support.
 *
 * Also the share-target entry point: ZIPs shared from other apps (or opened
 * from a file manager) are staged into the app cache and queued for import.
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
        handleIncomingZip(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm-start share/open events land here (launchMode singleTop not
        // set, so this also covers recreated activities after process death).
        handleIncomingZip(intent)
    }

    /**
     * Extracts a ZIP from ACTION_SEND (EXTRA_STREAM) or ACTION_VIEW and stages
     * it into the cache. Content URIs from other apps are transient — copying
     * immediately guarantees the data is readable whenever the import runs.
     */
    private fun handleIncomingZip(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return

        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "incoming").apply { mkdirs() }
                    val out = File(dir, "shared-${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    if (out.length() == 0L) {
                        out.delete()
                        null
                    } else {
                        Uri.fromFile(out)
                    }
                }.getOrNull()
            }
            if (staged != null) StratosApp.instance.offerImport(staged)
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
