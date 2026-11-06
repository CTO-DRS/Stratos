package app.stratos.host.data

import android.content.Context
import android.net.Uri
import app.stratos.host.engine.EngineApi
import app.stratos.host.engine.EngineSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * Owns site lifecycle: secure import (via the strongest available engine),
 * replace, delete, enable/disable, default selection — and produces the
 * engine-ready site list used at start and hot-reload.
 */
class SitesRepository(
    private val context: Context,
    private val dao: SiteDao,
    private val engine: EngineApi
) {

    companion object {
        val IMPORT_MIME = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
        private const val MAX_IMPORT_BYTES = 512L shl 20

        fun randomId(len: Int = 8): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            return buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        }
    }

    fun observeAll(): Flow<List<SiteEntity>> = dao.observeAll()

    suspend fun all(): List<SiteEntity> = dao.all()

    suspend fun defaultSite(): SiteEntity? = dao.defaultSite()

    suspend fun byId(id: String): SiteEntity? = dao.byId(id)

    data class ImportPreview(
        val stagingFile: File,
        val stagingDir: File,
        val summary: ZipSummaryUi,
        val suggestedName: String
    )

    data class ZipSummaryUi(
        val files: Int,
        val dirs: Int,
        val bytes: Long,
        val root: String,
        val hasIndex: Boolean,
        val entryCount: Int
    )

    /**
     * Stage 1: copy the SAF Uri into cache, validate + extract into a staging
     * directory, and return the summary for user confirmation. Nothing is
     * committed to the site registry yet.
     */
    suspend fun stageImport(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "import-${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staging.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        } ?: throw IllegalStateException("cannot read the selected file")
        try {
            stageStagedZip(staging)
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
    }

    /** Shared validation+extraction core for both SAF and local zips. */
    private suspend fun stageStagedZip(staging: File): ImportPreview = withContext(Dispatchers.IO) {
        if (staging.length() > MAX_IMPORT_BYTES) {
            staging.delete()
            throw IllegalStateException("archive larger than ${MAX_IMPORT_BYTES shr 20} MB")
        }

        val extractDir = File(context.filesDir, "sites/staging-${randomId(6)}")
        val summary = engine.extractZip(staging.absolutePath, extractDir.absolutePath)
            .getOrElse { staging.delete(); throw it }

        val name = staging.nameWithoutExtension
            .replace(Regex("^import-\\d+$"), "My Site")
            .replace('_', ' ').replace('-', ' ').trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "My Site" }

        ImportPreview(
            stagingFile = staging,
            stagingDir = extractDir,
            summary = ZipSummaryUi(summary.files, summary.dirs, summary.bytes, summary.root, summary.hasIndex, summary.entryCount),
            suggestedName = name
        )
    }

    /** Stage 2: commit the staged extraction into the registry as a real site. */
    suspend fun commitImport(preview: ImportPreview, name: String?, spa: Boolean, language: String): SiteEntity =
        withContext(Dispatchers.IO) {
            val stagingDir = preview.stagingDir
            if (!stagingDir.exists()) throw IllegalStateException("staged import disappeared")

            val id = randomId()
            val finalDir = File(context.filesDir, "sites/$id")
            if (!stagingDir.renameTo(finalDir)) {
                stagingDir.copyRecursively(finalDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            preview.stagingFile.delete()

            val siteRoot = resolveRoot(finalDir, preview.summary.root)
            val entity = SiteEntity(
                id = id,
                name = (name ?: preview.suggestedName).ifBlank { "Site" },
                rootPath = siteRoot.absolutePath,
                extractDir = finalDir.absolutePath,
                isDefault = dao.defaultSite() == null,
                enabled = true,
                spa = spa,
                filesCount = preview.summary.files,
                sizeBytes = preview.summary.bytes,
                hasIndex = preview.summary.hasIndex,
                language = language
            )
            dao.upsert(entity)
            entity
        }

    /** Re-import (replace) the content of an existing site, keeping its id. */
    suspend fun replaceContent(site: SiteEntity, zipFile: File): SiteEntity = withContext(Dispatchers.IO) {
        val preview = stageStagedZip(zipFile)
        val id = site.id
        val finalDir = File(context.filesDir, "sites/$id")
        File(site.extractDir).deleteRecursively()
        val stagingDir = preview.stagingDir
        if (!stagingDir.renameTo(finalDir)) {
            stagingDir.copyRecursively(finalDir, overwrite = true)
            stagingDir.deleteRecursively()
        }
        preview.stagingFile.delete()
        val siteRoot = resolveRoot(finalDir, preview.summary.root)
        val updated = site.copy(
            rootPath = siteRoot.absolutePath,
            extractDir = finalDir.absolutePath,
            filesCount = preview.summary.files,
            sizeBytes = preview.summary.bytes,
            hasIndex = preview.summary.hasIndex
        )
        dao.update(updated)
        updated
    }

    suspend fun delete(site: SiteEntity) = withContext(Dispatchers.IO) {
        dao.delete(site.id)
        File(site.extractDir).deleteRecursively()
        if (site.isDefault) {
            dao.all().firstOrNull()?.let { dao.setDefault(it.id) }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun setDefault(id: String) = dao.setDefault(id)

    suspend fun rename(site: SiteEntity, newName: String) {
        dao.update(site.copy(name = newName))
    }

    /**
     * Registers an already-materialised site directory (used by the AI
     * builder, which writes its generated HTML directly to storage).
     */
    suspend fun commitGeneratedSite(
        id: String,
        name: String,
        extractDir: File,
        hasIndex: Boolean,
        sizeBytes: Long,
        spa: Boolean,
        language: String
    ): SiteEntity = withContext(Dispatchers.IO) {
        val entity = SiteEntity(
            id = id,
            name = name,
            rootPath = extractDir.absolutePath,
            extractDir = extractDir.absolutePath,
            isDefault = dao.defaultSite() == null,
            enabled = true,
            spa = spa,
            filesCount = 1,
            sizeBytes = sizeBytes,
            hasIndex = hasIndex,
            language = language
        )
        dao.upsert(entity)
        entity
    }

    /** The engine view of all enabled sites (default first). */
    suspend fun engineSites(): List<EngineSite> {
        val sites = dao.all().filter { it.enabled }
        val def = sites.firstOrNull { it.isDefault } ?: sites.firstOrNull()
        return sites.map {
            EngineSite(
                id = it.id,
                name = it.name,
                root = it.rootPath,
                isDefault = it.id == (def?.id ?: ""),
                enabled = true,
                spa = it.spa
            )
        }
    }

    private fun resolveRoot(extractDir: File, rootRel: String): File =
        if (rootRel == "." || rootRel.isEmpty()) extractDir
        else File(extractDir, rootRel)
}
