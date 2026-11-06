package app.stratos.host.ui.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File

/**
 * Central TextMate wiring for the built-in code editor.
 *
 * One-shot initialization (idempotent, process-wide):
 *  1. Registers an AssetsFileResolver so TextMate can open bundled files.
 *  2. Loads the bundled grammars via `textmate/languages.json`
 *     (official VS Code grammars, MIT licensed).
 *  3. Loads both Stratos themes (light + dark) into the ThemeRegistry.
 *
 * Applying is fail-safe: if anything is missing the editor keeps its
 * default (unhighlighted) look instead of crashing.
 */
object SyntaxSupport {

    const val LIGHT_THEME = "stratos-light"
    const val DARK_THEME = "stratos-dark"

    private const val LANGUAGES_JSON = "textmate/languages.json"

    @Volatile
    private var grammarsLoaded = false

    @Volatile
    private var themesLoaded = false

    /** File extension → TextMate root scope. */
    private val scopeByExtension = mapOf(
        "html" to "text.html.basic",
        "htm" to "text.html.basic",
        "xhtml" to "text.html.basic",
        "css" to "source.css",
        "js" to "source.js",
        "mjs" to "source.js",
        "cjs" to "source.js",
        "json" to "source.json",
        "geojson" to "source.json",
        "webmanifest" to "source.json",
        "md" to "text.html.markdown",
        "markdown" to "text.html.markdown",
        "xml" to "text.xml",
        "svg" to "text.xml",
        "plist" to "text.xml",
        "yml" to "source.yaml",
        "yaml" to "source.yaml"
    )

    fun scopeFor(file: File?): String? {
        val ext = file?.extension?.lowercase() ?: return null
        return scopeByExtension[ext]
    }

    /** True when both grammars and themes are ready for highlighting. */
    fun init(context: Context): Boolean {
        val app = context.applicationContext
        if (!grammarsLoaded) {
            synchronized(this) {
                if (!grammarsLoaded) {
                    grammarsLoaded = runCatching {
                        FileProviderRegistry.getInstance()
                            .addFileProvider(AssetsFileResolver(app.assets))
                        GrammarRegistry.getInstance().loadGrammars(LANGUAGES_JSON)
                        true
                    }.getOrDefault(false)
                }
            }
        }
        if (!themesLoaded) {
            synchronized(this) {
                if (!themesLoaded) {
                    themesLoaded = runCatching {
                        loadThemeFromAssets(LIGHT_THEME, isDark = false)
                        loadThemeFromAssets(DARK_THEME, isDark = true)
                        true
                    }.getOrDefault(false)
                }
            }
        }
        return grammarsLoaded && themesLoaded
    }

    private fun loadThemeFromAssets(name: String, isDark: Boolean) {
        val path = "textmate/themes/$name.json"
        val stream = FileProviderRegistry.getInstance().tryGetInputStream(path)
            ?: throw IllegalStateException("theme asset missing: $path")
        val model = ThemeModel(
            IThemeSource.fromInputStream(stream, path, null),
            name
        ).apply { setDark(isDark) }
        ThemeRegistry.getInstance().loadTheme(model, isCurrentTheme = false)
    }

    /** Point ThemeRegistry at the light or dark Stratos theme (no-op if current). */
    fun selectTheme(isDark: Boolean) {
        val registry = ThemeRegistry.getInstance()
        val wanted = if (isDark) DARK_THEME else LIGHT_THEME
        val current = registry.currentThemeModel
        if (current.name == wanted) return
        runCatching { registry.setTheme(wanted) }
    }

    /**
     * Applies matching color scheme + language to [editor] for [file].
     * Returns true when highlighting is active. Never throws.
     */
    fun apply(editor: CodeEditor, file: File?, isDark: Boolean): Boolean {
        selectTheme(isDark)
        var themed = false
        runCatching {
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            themed = true
        }
        val scope = scopeFor(file)
        if (scope != null) {
            runCatching {
                editor.setEditorLanguage(TextMateLanguage.create(scope, false))
            }
        }
        return themed && scope != null
    }
}
