package app.stratos.host.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stratos full-backup format (v1) — the on-disk wire shape of
 * "your phone, your server, your rules" applied to the whole installation:
 *
 * ```
 * manifest.json                 BackupManifest  (format=1, app="stratos", …)
 * settings.json                 BackupSettings  (safe subset — never secrets)
 * sites/<id>/site.json          BackupSiteMeta  (name, rootRel, flags, …)
 * sites/<id>/files/<rel>        the site's content, relative to its extract dir
 * ```
 *
 * Design invariants, enforced in code — not by convention:
 *  - Secrets can never reach a backup: [BackupSettings] simply has no field
 *    for the vault-stored AI key, the tunnel token or the access-protection
 *    password, and `protect_user` / `onboarding_done` are deliberately
 *    excluded as well (a fresh device should greet its owner with its own
 *    introduction, and an empty username means "protection off" on restore).
 *  - Site ids are strictly `[a-z0-9]{4,32}` and root relationships are
 *    re-validated on restore, so a tampered archive cannot point a restored
 *    site outside its extraction directory.
 *  - This class is pure JVM (no Android imports) so the whole codec runs in
 *    plain unit tests.
 */

/** Thrown by [BackupCodec.parseStaged] when the archive is not a Stratos backup. */
class NotABackupException(message: String) : IllegalStateException(message)

@Serializable
data class BackupManifest(
    val format: Int,
    val app: String,
    val versionName: String,
    val exportedAt: String,
    val sites: Int
)

/**
 * The settings subset that travels inside a backup. Anything secret or
 * device-local (vault items, protectUser, onboarding state) is intentionally
 * absent — its absence is the security property, not a filter.
 */
@Serializable
data class BackupSettings(
    val port: Int,
    val bindAll: Boolean,
    val spaDefault: Boolean,
    val theme: String,
    val language: String,
    val editorScale: Int,
    val editorWrap: Boolean,
    val aiEndpoint: String,
    val aiModel: String,
    val aiAutoTunnel: Boolean,
    val customDomain: String
)

@Serializable
data class BackupSiteMeta(
    val id: String,
    val name: String,
    /** Where inside the extract dir the served root lives ("." = the dir itself). */
    val rootRel: String,
    val isDefault: Boolean,
    val enabled: Boolean,
    val spa: Boolean,
    val language: String,
    val createdAt: Long
)

object BackupCodec {

    const val FORMAT = 1
    const val APP_ID = "stratos"
    const val MANIFEST_ENTRY = "manifest.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val SITES_DIR = "sites"
    const val SITE_META_ENTRY = "site.json"
    const val FILES_DIR = "files"

    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val ID_RE = Regex("^[a-z0-9]{4,32}$")

    /** Site ids are engine-safe path segments produced by [SitesRepository.randomId]. */
    fun validSiteId(id: String): Boolean = ID_RE.matches(id)

    /**
     * A root relationship is either "." or a clean forward-slash relative
     * path: no drive letters, no backslashes, no leading slash, no empty or
     * dot-dot elements anywhere.
     */
    fun validRootRel(rel: String): Boolean {
        if (rel == ".") return true
        if (rel.isEmpty() || rel.startsWith('/') || rel.contains('\\')) return false
        return rel.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** One site in the archive being written: metadata JSON + its extract dir on disk. */
    data class SiteDir(val id: String, val metaJson: String, val extractDir: File)

    data class ArchiveStats(val sites: Int, val files: Int, val bytes: Long)

    /**
     * Stream the whole backup into [out]. Site files are walked with the same
     * canonical-path guard as site export, so a stray symlink can never
     * influence the layout; missing extract dirs simply yield metadata-only
     * sites instead of failing the whole archive.
     */
    fun writeBackup(
        out: OutputStream,
        manifest: BackupManifest,
        settings: BackupSettings?,
        sites: List<SiteDir>
    ): ArchiveStats {
        var files = 0
        var bytes = 0L
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            put(MANIFEST_ENTRY, json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
            if (settings != null) {
                put(SETTINGS_ENTRY, json.encodeToString(BackupSettings.serializer(), settings).encodeToByteArray())
            }
            for (site in sites) {
                val id = site.id
                if (!validSiteId(id)) throw NotABackupException("refusing to write a backup with invalid site id")
                put("$SITES_DIR/$id/$SITE_META_ENTRY", site.metaJson.encodeToByteArray())
                val base = site.extractDir.canonicalPath + File.separator
                val walk: Sequence<File> =
                    if (site.extractDir.isDirectory)
                        site.extractDir.walkTopDown().filter { it.isFile }
                            .filter { it.canonicalFile.path.startsWith(base) }
                    else
                        emptySequence()
                for (f in walk) {
                    val rel = f.canonicalFile.path.removePrefix(base).replace(File.separatorChar, '/')
                    if (rel.isEmpty()) continue
                    val entry = ZipEntry("$SITES_DIR/$id/$FILES_DIR/$rel")
                    val mtime = f.lastModified()
                    if (mtime > 0) entry.time = mtime
                    zip.putNextEntry(entry)
                    f.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                    files++
                    bytes += f.length()
                }
            }
        }
        return ArchiveStats(sites.size, files, bytes)
    }

    /** One site parsed from a staged backup: its metadata + the staging dir holding it. */
    data class RestoredSite(val dir: File, val meta: BackupSiteMeta)

    data class StagedBackup(
        val manifest: BackupManifest,
        val settings: BackupSettings?,
        val sites: List<RestoredSite>,
        val filesTotal: Int
    )

    /**
     * Read a staged backup directory (the output of the hardened engine
     * extractor) into a [StagedBackup]. A foreign or unsupported manifest
     * aborts the parse; individual broken site folders are skipped so one
     * bad entry can never discard a good restore.
     */
    fun parseStaged(stagedDir: File): StagedBackup {
        val manifestFile = File(stagedDir, MANIFEST_ENTRY)
        if (!manifestFile.isFile) throw NotABackupException("no manifest.json in the archive")
        val manifest = runCatching {
            json.decodeFromString(BackupManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
        }.getOrElse { throw NotABackupException("manifest.json is not readable") }
        if (manifest.app != APP_ID) throw NotABackupException("manifest app is '${manifest.app}'")
        if (manifest.format != FORMAT) throw NotABackupException("unsupported backup format ${manifest.format}")

        val settingsFile = File(stagedDir, SETTINGS_ENTRY)
        val settings: BackupSettings? = if (settingsFile.isFile)
            runCatching { json.decodeFromString(BackupSettings.serializer(), settingsFile.readText(Charsets.UTF_8)) }
                .getOrNull()
        else null

        val sitesRoot = File(stagedDir, SITES_DIR)
        val sites = mutableListOf<RestoredSite>()
        var filesTotal = 0
        if (sitesRoot.isDirectory) {
            for (dir in sitesRoot.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
                // A site folder's name is the id used on restore — enforce the
                // engine-safe charset before it can ever reach the filesystem.
                if (!validSiteId(dir.name)) continue
                val meta = runCatching {
                    json.decodeFromString(BackupSiteMeta.serializer(), File(dir, SITE_META_ENTRY).readText(Charsets.UTF_8))
                }.getOrNull() ?: continue
                if (meta.id != dir.name) continue
                filesTotal += File(dir, FILES_DIR).walkTopDown().count { it.isFile }
                sites += RestoredSite(dir, meta)
            }
        }
        return StagedBackup(manifest, settings, sites, filesTotal)
    }
}
