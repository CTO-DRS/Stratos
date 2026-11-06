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
        val f = tmp.newFile("site-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    /**
     * Builds a raw STORE-method ZIP containing `dups` identical entries.
     * JDK's ZipOutputStream refuses to write duplicate names, so the bytes
     * are written by hand (valid central directory + EOCD, stored method).
     */
    private fun rawDuplicateZip(dups: Int): File {
        val f = tmp.newFile("dup-${System.nanoTime()}.zip")
        val name = "a.txt".toByteArray(Charsets.US_ASCII)
        val data = "x".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        fun le(b: java.io.ByteArrayOutputStream, v: Int, n: Int) {
            for (i in 0 until n) b.write((v shr (8 * i)) and 0xFF)
        }
        val local = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        var offset = 0
        repeat(dups) {
            offset += local.size()
            le(local, 0x04034b50, 4); le(local, 20, 2); le(local, 0, 2); le(local, 0, 2)
            le(local, 0, 2); le(local, 0, 2)
            le(local, crc.toInt(), 4); le(local, data.size, 4); le(local, data.size, 4)
            le(local, name.size, 2); le(local, 0, 2)
            local.write(name); local.write(data)

            le(central, 0x02014b50, 4); le(central, 20, 2); le(central, 20, 2)
            le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 2)
            le(central, crc.toInt(), 4); le(central, data.size, 4); le(central, data.size, 4)
            le(central, name.size, 2); le(central, 0, 2)
            le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 4)
            le(central, offset, 4)
            central.write(name)
        }
        val cd = central.toByteArray()
        val eocd = java.io.ByteArrayOutputStream()
        le(eocd, 0x06054b50, 4); le(eocd, 0, 2); le(eocd, 0, 2)
        le(eocd, dups, 2); le(eocd, dups, 2); le(eocd, cd.size, 4)
        // The central directory starts right after ALL local records.
        le(eocd, local.size(), 4)
        le(eocd, 0, 2)
        f.writeBytes(local.toByteArray() + cd + eocd.toByteArray())
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
        val f = rawDuplicateZip(2)
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
