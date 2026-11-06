package app.stratos.host

import app.stratos.host.security.RootDetect
import app.stratos.host.security.ZipSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for the Kotlin ZIP security layer — the same threat model the Go
 * engine enforces. Run in Android Studio: ./gradlew :app:testDebugUnitTest
 */
class ZipSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipOf(entries: Map<String, String>): File {
        val f = tmp.newFile("site.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `happy path extracts and detects root`() {
        val zip = zipOf(mapOf(
            "index.html" to "<h1>Hi</h1>",
            "css/main.css" to "body{}",
            "img/logo.svg" to "<svg/>"
        ))
        val dest = tmp.newFolder("site")
        val r = ZipSecurity.validateAndExtract(zip, dest)
        assertEquals(3, r.files)
        assertEquals(".", r.root)
        assertTrue(r.hasIndex)
        assertTrue(File(dest, "css/main.css").isFile)
    }

    @Test
    fun `single top folder becomes root`() {
        val zip = zipOf(mapOf(
            "dist/index.html" to "<h1>D</h1>",
            "dist/app.js" to "console.log(1)"
        ))
        val r = ZipSecurity.validateAndExtract(zip, tmp.newFolder("site"))
        assertEquals("dist", r.root)
        assertTrue(r.hasIndex)
    }

    @Test
    fun `zip slip traversal is rejected`() {
        val zip = zipOf(mapOf("index.html" to "ok", "../evil.txt" to "nope"))
        val dest = File(tmp.root, "site")
        try {
            ZipSecurity.validateAndExtract(zip, dest)
            fail("expected rejection")
        } catch (e: ZipSecurity.ValidationException) {
            assertTrue(e.message!!.contains("traversal"))
        }
        // No partial destination may be committed
        assertFalse(dest.exists() && dest.listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun `absolute and drive paths rejected`() {
        try {
            ZipSecurity.validateAndExtract(
                zipOf(mapOf("index.html" to "ok", "/etc/passwd" to "x")),
                tmp.newFolder("a")
            )
            fail("expected rejection")
        } catch (e: ZipSecurity.ValidationException) {
            assertTrue(e.message!!.contains("absolute"))
        }
        try {
            ZipSecurity.validateAndExtract(
                zipOf(mapOf("index.html" to "ok", "C:\\evil.txt" to "x")),
                tmp.newFolder("b")
            )
            fail("expected rejection")
        } catch (e: ZipSecurity.ValidationException) {
            assertTrue(e.message!!.contains("drive-letter"))
        }
    }

    @Test
    fun `duplicate names rejected`() {
        val f = tmp.newFile("dup.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            repeat(2) {
                zos.putNextEntry(ZipEntry("a.txt"))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        try {
            ZipSecurity.validateAndExtract(f, tmp.newFolder("c"))
            fail("expected rejection")
        } catch (e: ZipSecurity.ValidationException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun `sanitize rejects NUL and backslash traversal`() {
        try { ZipSecurity.sanitize("a\u0000b"); fail() } catch (_: ZipSecurity.ValidationException) {}
        try { ZipSecurity.sanitize("..\\evil"); fail() } catch (_: ZipSecurity.ValidationException) {}
        assertEquals("a/b", ZipSecurity.sanitize("a\\b"))
    }

    @Test
    fun `root detect falls back with hasIndex false`() {
        val dir = tmp.newFolder("plain")
        File(dir, "readme.txt").writeText("no index here")
        val (root, hasIndex) = RootDetect.detect(dir)
        assertEquals(".", root)
        assertFalse(hasIndex)
    }

    @Test
    fun `public folder is detected`() {
        val dir = tmp.newFolder("proj")
        File(dir, "public").mkdirs()
        File(dir, "public/index.html").writeText("<h1>P</h1>")
        val (root, hasIndex) = RootDetect.detect(dir)
        assertEquals("public", root)
        assertTrue(hasIndex)
    }
}
