package app.stratos.host

import app.stratos.host.data.BackupCodec
import app.stratos.host.data.BackupManifest
import app.stratos.host.data.BackupSettings
import app.stratos.host.data.BackupSiteMeta
import app.stratos.host.data.NotABackupException
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Full-backup codec: round-trip fidelity, foreign-archive rejection,
 * id/root-rel validation, symlink confinement and the "no secrets in the
 * settings payload" invariant.
 */
class BackupCodecTest {

    private fun manifest(sites: Int = 1) = BackupManifest(
        format = BackupCodec.FORMAT, app = BackupCodec.APP_ID,
        versionName = "1.0.0", exportedAt = "2026-11-08T00:00:00Z", sites = sites
    )

    private fun settings() = BackupSettings(
        port = 8080, bindAll = true, spaDefault = true, theme = "dark",
        language = "ar", editorScale = 120, editorWrap = true,
        aiEndpoint = "https://api.example.com/v1", aiModel = "glm-4-flash",
        aiAutoTunnel = false, customDomain = "test.example.org"
    )

    private fun siteMeta(rootRel: String = ".") = BackupSiteMeta(
        id = "a1b2c3d4", name = "My Site", rootRel = rootRel,
        isDefault = true, enabled = true, spa = false,
        language = "en", createdAt = 1_700_000_000_000
    )

    /** Unzip bytes with the plain JDK stream, into a directory (test helper). */
    private fun unzip(bytes: ByteArray, into: File) {
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(into, e.name)
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
    }

    @Test
    fun `write then parse round-trips manifest settings and site payloads`() {
        val tmp = Files.createTempDirectory("stratos-bk").toFile()
        val extract = File(tmp, "extract").apply { mkdirs() }
        File(extract, "index.html").writeText("<h1>hello</h1>")
        File(extract, "assets").mkdirs()
        File(extract, "assets/style.css").writeText("body{color:#0af}")
        val meta = siteMeta(rootRel = ".")
        val siteDir = BackupCodec.SiteDir(
            id = meta.id,
            metaJson = BackupCodec.json.encodeToString(BackupSiteMeta.serializer(), meta),
            extractDir = extract
        )

        val archive = File(tmp, "backup.zip")
        val stats = archive.outputStream().use { out ->
            BackupCodec.writeBackup(out, manifest(sites = 1), settings(), listOf(siteDir))
        }
        assertEquals(1, stats.sites)
        assertEquals(2, stats.files)
        assertTrue(stats.bytes > 0)

        val staged = Files.createTempDirectory("stratos-stage").toFile()
        unzip(archive.readBytes(), staged)
        val parsed = BackupCodec.parseStaged(staged)

        assertEquals(BackupCodec.APP_ID, parsed.manifest.app)
        assertEquals(BackupCodec.FORMAT, parsed.manifest.format)
        assertEquals("1.0.0", parsed.manifest.versionName)
        assertEquals(1, parsed.manifest.sites)
        assertEquals(8080, parsed.settings?.port)
        assertEquals("ar", parsed.settings?.language)
        assertEquals(120, parsed.settings?.editorScale)
        assertEquals(1, parsed.sites.size)
        val restored = parsed.sites.first()
        assertEquals("a1b2c3d4", restored.meta.id)
        assertEquals("My Site", restored.meta.name)
        assertEquals(".", restored.meta.rootRel)
        assertEquals("<h1>hello</h1>", File(restored.dir, "files/index.html").readText())
        assertEquals("body{color:#0af}", File(restored.dir, "files/assets/style.css").readText())
        assertEquals(2, parsed.filesTotal)
        tmp.deleteRecursively(); staged.deleteRecursively()
    }

    @Test
    fun `missing or foreign or unsupported manifests are rejected`() {
        val tmp = Files.createTempDirectory("stratos-bk2").toFile()

        // no manifest at all
        try {
            BackupCodec.parseStaged(tmp)
            fail("expected NotABackupException for a missing manifest")
        } catch (e: NotABackupException) { /* expected */ }

        // foreign app
        val foreign = File(tmp, "foreign").apply { mkdirs() }
        File(foreign, "manifest.json").writeText(
            BackupCodec.json.encodeToString(
                BackupManifest.serializer(),
                manifest().copy(app = "other-app")
            )
        )
        try {
            BackupCodec.parseStaged(foreign)
            fail("expected NotABackupException for a foreign app id")
        } catch (e: NotABackupException) { /* expected */ }

        // future format
        val future = File(tmp, "future").apply { mkdirs() }
        File(future, "manifest.json").writeText(
            BackupCodec.json.encodeToString(
                BackupManifest.serializer(),
                manifest().copy(format = 99)
            )
        )
        try {
            BackupCodec.parseStaged(future)
            fail("expected NotABackupException for an unsupported format")
        } catch (e: NotABackupException) { /* expected */ }
        tmp.deleteRecursively()
    }

    @Test
    fun `site folders with hostile or mismatched ids are skipped, not fatal`() {
        val tmp = Files.createTempDirectory("stratos-bk3").toFile()
        val staged = File(tmp, "staged").apply { mkdirs() }
        File(staged, "manifest.json").writeText(
            BackupCodec.json.encodeToString(BackupManifest.serializer(), manifest(sites = 2))
        )
        // a hostile folder name (would-be traversal) and a valid one
        File(staged, "sites/..evil").apply { mkdirs() }  // dots are invalid ids
        val good = File(staged, "sites/a1b2c3d4").apply { mkdirs() }
        File(good, "site.json").writeText(
            BackupCodec.json.encodeToString(BackupSiteMeta.serializer(), siteMeta())
        )
        // an id mismatch (folder vs payload) must also be skipped
        val mismatch = File(staged, "sites/ffff0001").apply { mkdirs() }
        File(mismatch, "site.json").writeText(
            BackupCodec.json.encodeToString(BackupSiteMeta.serializer(), siteMeta().copy(id = "ffff0002"))
        )
        val parsed = BackupCodec.parseStaged(staged)
        assertEquals(1, parsed.sites.size)
        assertEquals("a1b2c3d4", parsed.sites.first().meta.id)
        tmp.deleteRecursively()
    }

    @Test
    fun `site ids and root rels are validated strictly`() {
        assertTrue(BackupCodec.validSiteId("a1b2c3d4"))
        assertTrue(BackupCodec.validSiteId("zz00"))
        assertFalse(BackupCodec.validSiteId("abc"))          // too short
        assertFalse(BackupCodec.validSiteId("ABCDEF01"))      // uppercase
        assertFalse(BackupCodec.validSiteId("../evil"))       // traversal
        assertFalse(BackupCodec.validSiteId("with/slash"))
        assertFalse(BackupCodec.validSiteId(""))

        assertTrue(BackupCodec.validRootRel("."))
        assertTrue(BackupCodec.validRootRel("public"))
        assertTrue(BackupCodec.validRootRel("public/dist"))
        assertFalse(BackupCodec.validRootRel("../up"))
        assertFalse(BackupCodec.validRootRel("/absolute"))
        assertFalse(BackupCodec.validRootRel("back\\slash"))
        assertFalse(BackupCodec.validRootRel("a//b"))
        assertFalse(BackupCodec.validRootRel("a/./b"))
    }

    @Test
    fun `settings payload never contains secret or device-local fields`() {
        val json = BackupCodec.json.encodeToString(BackupSettings.serializer(), settings())
        for (forbidden in listOf("protect_user", "protectUser", "ai_api_key", "apiKey", "token", "password", "onboarding")) {
            assertFalse("settings payload leaked '$forbidden'", json.contains(forbidden, ignoreCase = true))
        }
        // and the serializable shape itself has no such property to fill
        val names = BackupSettings.serializer().descriptor.elementNames.toList()
        assertFalse(names.contains("protectUser"))
        assertFalse(names.contains("onboardingDone"))
    }

    @Test
    fun `symlinks escaping the extract dir are never packed`() {
        val tmp = Files.createTempDirectory("stratos-bk4").toFile()
        val extract = File(tmp, "extract").apply { mkdirs() }
        val outside = File(tmp, "outside-secret.txt")
        outside.writeText("should not travel")
        try {
            Files.createSymbolicLink(
                File(extract, "escape.link").toPath(),
                File(tmp, "outside-secret.txt").toPath()
            )
        } catch (e: UnsupportedOperationException) {
            return // filesystem without symlink support — the guard is moot
        }
        val meta = siteMeta()
        val siteDir = BackupCodec.SiteDir(
            id = meta.id,
            metaJson = BackupCodec.json.encodeToString(BackupSiteMeta.serializer(), meta),
            extractDir = extract
        )
        val archive = File(tmp, "backup.zip")
        val stats = archive.outputStream().use { out ->
            BackupCodec.writeBackup(out, manifest(), settings(), listOf(siteDir))
        }
        assertEquals(0, stats.files)
        val names = ZipInputStream(ByteArrayInputStream(archive.readBytes()), Charsets.UTF_8).use { zin ->
            generateSequence { zin.nextEntry }.map { it.name }.toList()
        }
        assertTrue(names.none { it.contains("escape.link") })
        assertTrue(outside.isFile) // untouched, and never entered the archive
        tmp.deleteRecursively()
    }
}
