package app.stratos.host.data

import android.content.Context
import android.net.Uri
import app.stratos.host.engine.EngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full backup & migrate — the whole installation (every site + the safe
 * settings subset) leaves and re-enters through one archive.
 *
 * Export streams a [BackupCodec] archive straight into the SAF-provided
 * stream (no temp copy). Restore reuses the hardened engine extractor —
 * the exact zip-slip / bomb / duplicate / CRC pipeline that guards site
 * import — and then only materialises sites whose ids pass
 * [BackupCodec.validSiteId]. Restored sites are always ADDED: a colliding
 * id gets a fresh one, so nothing the user currently hosts is ever
 * overwritten. Secrets never participate: the vault (AI key, tunnel token,
 * access-protection password) is not part of the format, and an empty
 * protection username simply means "protection off" until the owner sets
 * it again.
 */
class BackupManager(
    private val context: Context,
    private val dao: SiteDao,
    private val repository: SitesRepository,
    private val settings: SettingsStore,
    private val engine: EngineApi
) {

    companion object {
        /** Same mime spread the site importer accepts. */
        val BACKUP_MIME = arrayOf(
            "application/zip", "application/x-zip-compressed", "application/octet-stream"
        )
        private const val MAX_BACKUP_BYTES = 512L shl 20

        fun suggestedFileName(): String {
            val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            return "stratos-backup-$ts.zip"
        }
    }

    data class ExportResult(val sites: Int, val files: Int, val bytes: Long)

    data class RestorePreview(
        val stagingZip: File,
        val stagingDir: File,
        val manifest: BackupManifest,
        val settings: BackupSettings?,
        val sites: List<BackupCodec.RestoredSite>,
        val filesTotal: Int
    )

    data class RestoreResult(val sites: Int, val skipped: Int, val settingsApplied: Boolean)

    /** Build and stream the full backup into the user-chosen destination. */
    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val sites = repository.all()
        val siteDirs = sites.map { s ->
            val meta = BackupSiteMeta(
                id = s.id,
                name = s.name,
                rootRel = rootRelOf(s),
                isDefault = s.isDefault,
                enabled = s.enabled,
                spa = s.spa,
                language = s.language,
                createdAt = s.createdAt
            )
            BackupCodec.SiteDir(s.id, BackupCodec.json.encodeToString(BackupSiteMeta.serializer(), meta), File(s.extractDir))
        }
        val current = settings.settings.first()
        val payload = BackupSettings(
            port = current.port,
            bindAll = current.bindAll,
            spaDefault = current.spaDefault,
            theme = current.theme,
            language = current.language,
            editorScale = current.editorScale,
            editorWrap = current.editorWrap,
            aiEndpoint = current.aiEndpoint,
            aiModel = current.aiModel,
            aiAutoTunnel = current.aiAutoTunnel,
            customDomain = current.customDomain
        )
        val manifest = BackupManifest(
            format = BackupCodec.FORMAT,
            app = BackupCodec.APP_ID,
            versionName = versionName(),
            exportedAt = utcNow(),
            sites = sites.size
        )
        val stats = context.contentResolver.openOutputStream(uri)?.use { out ->
            BackupCodec.writeBackup(out, manifest, payload, siteDirs)
        } ?: throw IllegalStateException("cannot open the chosen destination")
        ExportResult(stats.sites, stats.files, stats.bytes)
    }

    /**
     * Stage 1 of restore: copy the chosen archive into cache, push it through
     * the engine's guarded extractor, and parse the staged tree. Nothing
     * touches the site registry yet.
     */
    suspend fun stageRestore(uri: Uri): RestorePreview = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${System.currentTimeMillis()}.zip")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            staging.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            true
        } ?: false
        if (!copied) throw IllegalStateException("cannot read the selected file")
        if (staging.length() > MAX_BACKUP_BYTES) {
            staging.delete()
            throw IllegalStateException("archive larger than ${MAX_BACKUP_BYTES shr 20} MB")
        }

        val extractDir = File(context.filesDir, "restore-staging-${SitesRepository.randomId(6)}")
        try {
            engine.extractZip(staging.absolutePath, extractDir.absolutePath).getOrThrow()
            val parsed = BackupCodec.parseStaged(extractDir)
            RestorePreview(
                stagingZip = staging,
                stagingDir = extractDir,
                manifest = parsed.manifest,
                settings = parsed.settings,
                sites = parsed.sites,
                filesTotal = parsed.filesTotal
            )
        } catch (t: Throwable) {
            staging.delete()
            extractDir.deleteRecursively()
            throw t
        }
    }

    /** Stage 2 of restore: materialise the previewed sites + settings subset. */
    suspend fun commitRestore(preview: RestorePreview): RestoreResult = withContext(Dispatchers.IO) {
        val hadDefault = dao.defaultSite() != null
        var restored = 0
        var skipped = 0
        val restoredIds = mutableListOf<Pair<String, BackupSiteMeta>>()

        for (site in preview.sites) {
            try {
                val src = File(site.dir, BackupCodec.FILES_DIR)
                // A colliding id means the user already hosts something with
                // that name — the backup becomes a NEW site instead of a
                // silent overwrite.
                val id = if (dao.byId(site.meta.id) == null) site.meta.id else SitesRepository.randomId()
                val finalDir = File(context.filesDir, "sites/$id")
                if (finalDir.exists()) finalDir.deleteRecursively()
                if (src.isDirectory) {
                    if (!src.renameTo(finalDir)) {
                        src.copyRecursively(finalDir, overwrite = true)
                        src.deleteRecursively()
                    }
                } else {
                    finalDir.mkdirs()
                }

                val rootRel = if (BackupCodec.validRootRel(site.meta.rootRel)) site.meta.rootRel else "."
                val root = resolveRoot(finalDir, rootRel)
                var filesCount = 0
                var sizeBytes = 0L
                root.walkTopDown().filter { it.isFile }.forEach { filesCount++; sizeBytes += it.length() }
                val hasIndex = File(root, "index.html").isFile || File(root, "index.htm").isFile

                dao.upsert(
                    SiteEntity(
                        id = id,
                        name = site.meta.name.ifBlank { "Site" },
                        rootPath = root.absolutePath,
                        extractDir = finalDir.absolutePath,
                        isDefault = false,
                        enabled = site.meta.enabled,
                        spa = site.meta.spa,
                        filesCount = filesCount,
                        sizeBytes = sizeBytes,
                        hasIndex = hasIndex,
                        language = if (site.meta.language in listOf("en", "ar", "tr")) site.meta.language else "en",
                        createdAt = if (site.meta.createdAt > 0) site.meta.createdAt else System.currentTimeMillis()
                    )
                )
                restoredIds += id to site.meta
                restored++
            } catch (t: Throwable) {
                skipped++
            } finally {
                site.dir.deleteRecursively()
            }
        }

        // Only when the device has no default site at all do we honour the
        // archive's own "default" flag — never demote an existing default.
        if (!hadDefault && restoredIds.isNotEmpty()) {
            val want = restoredIds.firstOrNull { it.second.isDefault }?.first ?: restoredIds.first().first
            dao.setDefault(want)
        }

        // Settings subset — coerced to the same bounds the app enforces
        // elsewhere; secrets and onboarding state are not in the payload.
        var settingsApplied = false
        preview.settings?.let { s ->
            settings.setPort(s.port.coerceIn(1024, 65535))
            settings.setBindAll(s.bindAll)
            settings.setSpaDefault(s.spaDefault)
            settings.setTheme(if (s.theme in listOf("light", "dark", "system")) s.theme else "system")
            settings.setLanguage(if (s.language in listOf("en", "ar", "tr")) s.language else "system")
            settings.setEditorScale(s.editorScale)
            settings.setEditorWrap(s.editorWrap)
            settings.setAiEndpoint(s.aiEndpoint)
            settings.setAiModel(s.aiModel)
            settings.setAiAutoTunnel(s.aiAutoTunnel)
            settings.setCustomDomain(s.customDomain)
            settingsApplied = true
        }

        cleanup(preview)
        RestoreResult(restored, skipped, settingsApplied)
    }

    /** Free the staging artifacts (archive copy + extracted tree). */
    fun cleanup(preview: RestorePreview) {
        preview.stagingZip.delete()
        preview.stagingDir.deleteRecursively()
    }

    private fun resolveRoot(extractDir: File, rootRel: String): File =
        if (rootRel == "." || rootRel.isEmpty()) extractDir else File(extractDir, rootRel)

    /**
     * Where the served root lives inside the extract dir, as a clean relative
     * path ("." when it is the dir itself); anything unexpected falls back
     * to "." so a restored site always serves its own content.
     */
    private fun rootRelOf(site: SiteEntity): String {
        val extract = File(site.extractDir)
        val root = File(site.rootPath)
        return runCatching {
            val base = extract.canonicalPath + File.separator
            val rootCanon = root.canonicalPath
            when {
                rootCanon == extract.canonicalPath -> "."
                rootCanon.startsWith(base) ->
                    rootCanon.removePrefix(base).replace(File.separatorChar, '/')
                else -> "."
            }
        }.getOrDefault(".")
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
