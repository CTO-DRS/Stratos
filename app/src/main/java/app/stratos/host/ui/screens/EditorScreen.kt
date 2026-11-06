package app.stratos.host.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.data.SettingsStore
import app.stratos.host.data.SiteEntity
import app.stratos.host.host.HostingController
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.editor.SyntaxSupport
import app.stratos.host.ui.theme.StratosColors
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignJustify
import java.io.File

/** A flattened, expandable view of the site's file tree. */
data class FileNode(
    val file: File,
    val depth: Int,
    val isDir: Boolean,
    val expanded: Boolean
)

class EditorViewModel(private val siteId: String) : ViewModel() {

    private val graph = StratosApp.instance.graph

    val site = MutableStateFlow<SiteEntity?>(null)
    val nodes = MutableStateFlow<List<FileNode>>(emptyList())
    val openFile = MutableStateFlow<File?>(null)
    val fileContent = MutableStateFlow("")
    val dirty = MutableStateFlow(false)
    val saved = MutableStateFlow(false)
    val settings = graph.settings.settings

    private var expandedDirs = mutableSetOf<String>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val s = graph.repository.byId(siteId)
            site.value = s
            rebuildTree()
            s?.let { openRelativeFirstHtml(it) }
        }
    }

    private suspend fun openRelativeFirstHtml(s: SiteEntity) {
        val root = File(s.rootPath)
        val candidates = listOf("index.html", "index.htm")
        for (c in candidates) {
            val f = File(root, c)
            if (f.isFile) { open(f); return }
        }
        root.walkTopDown().firstOrNull { it.isFile && it.extension in listOf("html", "htm") }
            ?.let { open(it) }
    }

    private fun rebuildTree() {
        val s = site.value ?: return
        val root = File(s.rootPath)
        val out = mutableListOf<FileNode>()

        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            ) ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                val isDir = child.isDirectory
                val expanded = isDir && expandedDirs.contains(child.absolutePath)
                out.add(FileNode(child, depth, isDir, expanded))
                if (expanded) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        nodes.value = out
    }

    fun toggleDir(node: FileNode) {
        val path = node.file.absolutePath
        if (expandedDirs.contains(path)) expandedDirs.remove(path) else expandedDirs.add(path)
        rebuildTree()
    }

    fun open(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { file.readText() }.getOrDefault("")
            openFile.value = file
            fileContent.value = text
            dirty.value = false
            saved.value = false
        }
    }

    fun updateContent(text: String) {
        if (text != fileContent.value) {
            fileContent.value = text
            dirty.value = true
        }
    }

    fun save() {
        val file = openFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { file.writeText(fileContent.value) }
                .onSuccess {
                    dirty.value = false
                    saved.value = true
                }
        }
    }

    class Factory(private val siteId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(siteId) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(nav: NavHostController, siteId: String, vm: EditorViewModel = viewModel(key = "editor_$siteId", factory = EditorViewModel.Factory(siteId))) {
    val site by vm.site.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val openFile by vm.openFile.collectAsState()
    val fileContent by vm.fileContent.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val saved by vm.saved.collectAsState()
    val settings by vm.settings.collectAsState(initial = app.stratos.host.data.Settings())

    val hostState = StratosApp.instance.graph.controller.state.collectAsState().value

    var mode by rememberSaveable { mutableStateOf(0) } // 0 = files, 1 = preview
    var reloadKey by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            title = {
                Column {
                    Text(site?.name ?: stringResource(R.string.editor), style = MaterialTheme.typography.titleMedium)
                    openFile?.let {
                        Text(
                            it.relativeTo(File(site!!.rootPath)).path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            actions = {
                if (dirty) {
                    IconButton(onClick = { vm.save() }) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.save))
                    }
                } else if (saved) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null,
                        tint = StratosColors.Success, modifier = Modifier.padding(end = 14.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )

        // Mode switch: Files | Preview + editor zoom / wrap controls
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.tab_files)) }
                SegmentedButton(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.tab_preview)) }
            }
            if (mode == 0) {
                // Text-size stepper — persisted, applied live to the editor.
                val scope = rememberCoroutineScope()
                val scale = settings.editorScale
                IconButton(
                    onClick = { scope.launch { StratosApp.instance.graph.settings.setEditorScale(scale - 10) } },
                    enabled = scale > 60,
                    modifier = Modifier.size(34.dp)
                ) {
                    Text("A\u2212", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "$scale%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { scope.launch { StratosApp.instance.graph.settings.setEditorScale(scale + 10) } },
                    enabled = scale < 200,
                    modifier = Modifier.size(34.dp)
                ) {
                    Text("A+", style = MaterialTheme.typography.labelLarge)
                }
                // Word-wrap toggle.
                IconButton(
                    onClick = { scope.launch { StratosApp.instance.graph.settings.setEditorWrap(!settings.editorWrap) } },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (settings.editorWrap) Icons.Filled.FormatAlignJustify else Icons.Filled.FormatAlignLeft,
                        contentDescription = stringResource(R.string.editor_wrap),
                        tint = if (settings.editorWrap) StratosColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (mode == 1) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }
        }

        if (mode == 0) {
            Row(Modifier.weight(1f)) {
                // File tree (left)
                Column(
                    Modifier
                        .width(180.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    LazyColumn {
                        items(nodes, key = { it.file.absolutePath }) { node ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (node.isDir) vm.toggleDir(node) else vm.open(node.file)
                                    }
                                    .padding(start = (10 + node.depth * 14).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (node.isDir) StratosColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    node.file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    color = if (node.file == openFile) StratosColors.Accent else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                // Code editor (right)
                Column(Modifier.weight(1f)) {
                    if (openFile == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.editor_pick_file),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        CodeEditorView(
                            content = fileContent,
                            onContentChanged = { vm.updateContent(it) },
                            file = openFile,
                            scalePercent = settings.editorScale,
                            wrap = settings.editorWrap
                        )
                    }
                }
            }
        } else {
            // Loopback-only preview; requires hosting to be running.
            val runningUrl = (hostState as? app.stratos.host.host.HostState.Running)?.loopbackUrl
            if (runningUrl == null || site == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.preview_needs_hosting), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                val secondary = if (site!!.isDefault) "" else "s/${site!!.id}/"
                PreviewWebView(url = runningUrl + secondary, reloadKey = reloadKey)
            }
        }
    }
}

/** sora-editor wrapped for Compose. Line numbers on; content synced on change.
 *  Syntax highlighting via bundled TextMate grammars (VS Code sets, MIT).
 *  Falls back silently to plain text if the engine cannot initialize.
 *  Text size and word-wrap follow the persisted editor settings. */
@Composable
private fun CodeEditorView(
    content: String,
    onContentChanged: (String) -> Unit,
    file: File?,
    scalePercent: Int,
    wrap: Boolean
) {
    val context = LocalContext.current
    val dark = app.stratos.host.ui.theme.stratosIsDark()
    val textMateReady = remember { SyntaxSupport.init(context) }
    val editor = remember {
        CodeEditor(context).apply {
            setLineNumberEnabled(true)
            isWordwrap = false
            typefaceText = android.graphics.Typeface.MONOSPACE
            setTextSize(13f)
            // Subscribe ONCE: push edits back to the ViewModel.
            subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) { event, _ ->
                onContentChanged(event.editor.text.toString())
            }
        }
    }

    // Apply highlighting per file and re-theme on dark/light change, then pin
    // the 2035 surface palette back on top of the token theme.
    LaunchedEffect(textMateReady, file, dark) {
        if (textMateReady) {
            SyntaxSupport.apply(editor, file, dark)
        }
        applyEditorScheme(editor, dark)
    }

    // Live text-size / word-wrap from the persisted editor settings.
    LaunchedEffect(scalePercent, wrap) {
        editor.setTextSize(13f * scalePercent.coerceIn(60, 200) / 100f)
        editor.isWordwrap = wrap
    }

    // Push external content changes into the editor (e.g. file switch).
    LaunchedEffect(content) {
        if (editor.text.toString() != content) {
            editor.setText(content)
        }
    }

    // Keep the editor surface in sync with the Stratos 2035 theme.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { editor },
        update = { e -> applyEditorScheme(e, dark) }
    )

    DisposableEffect(Unit) {
        onDispose { editor.release() }
    }
}

/** Theme-aware palette for the sora editor (Stratos 2035 glass tones).
 *  Mutates the editor's CURRENT scheme — never replaces it — so TextMate
 *  token colors installed by SyntaxSupport survive; only surface keys are
 *  pinned here (background, line numbers, current line, selection). */
private fun applyEditorScheme(editor: CodeEditor, dark: Boolean) {
    val s = editor.colorScheme ?: return
    if (dark) {
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, 0xFF0B0E18.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, 0xFF0B0E18.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER, 0xFF8C93AD.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_CURRENT, 0xFF5EA2FF.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL, 0xFFF2F5FF.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE, 0xFF131828.toInt())
    } else {
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, 0xFFFDFFFF.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, 0xFFFDFFFF.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER, 0xFF8A93A8.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_CURRENT, 0xFF0B6BFF.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL, 0xFF0B1020.toInt())
        s.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE, 0xFFEEF2FC.toInt())
    }
    editor.setColorScheme(s)
}

/** Loopback-locked preview: JS on, everything else locked down. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(url: String, reloadKey: Int = 0) {
    val context = LocalContext.current

    val webView = remember(url, reloadKey) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            webViewClient = WebViewClient()
            loadUrl(url)
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        factory = { webView },
        update = { w ->
            if (w.url != url) w.loadUrl(url)
        }
    )

    DisposableEffect(Unit) { onDispose { webView.destroy() } }
}
