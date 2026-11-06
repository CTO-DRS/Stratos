package app.stratos.host

import app.stratos.host.engine.normalize
import app.stratos.host.engine.parseRange
import app.stratos.host.engine.resolveConfined
import app.stratos.host.util.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the Kotlin fallback engine's path/range helpers and for the
 * credential redaction used by Kotlin-side logs.
 */
class EngineHelpersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- normalize -----------------------------------------------------------

    @Test
    fun `normalize keeps clean relative paths`() {
        assertEquals(".", normalize("/"))
        assertEquals("a/b", normalize("/a/b"))
        assertEquals("a/b", normalize("a/b/"))
    }

    @Test
    fun `normalize refuses traversal and NUL`() {
        assertNull(normalize("/../secret"))
        assertNull(normalize("/a/../../b"))
        assertNull(normalize("/a\u0000b"))
    }

    // ---- resolveConfined -----------------------------------------------------

    @Test
    fun `resolve stays inside the root`() {
        val root = tmp.newFolder("root")
        val inside = File(root, "sub/index.html")
        inside.parentFile.mkdirs()
        inside.writeText("x")

        assertEquals(inside.canonicalFile, resolveConfined(root, "sub/index.html"))
        assertEquals(root.canonicalFile, resolveConfined(root, "."))
    }

    @Test
    fun `resolve refuses escape`() {
        val root = tmp.newFolder("root")
        assertNull(resolveConfined(root, "../outside.txt"))
    }

    // ---- parseRange ----------------------------------------------------------

    @Test
    fun `single range parses`() {
        assertEquals(0L to 99L, parseRange("bytes=0-99", 4096))
        assertEquals(100L to 4095L, parseRange("bytes=100-", 4096))
    }

    @Test
    fun `invalid ranges fall back to full body`() {
        assertNull(parseRange("bytes=0-99,200-299", 4096))   // multi-range
        assertNull(parseRange("bytes=-99", 4096))            // suffix (unsupported)
        assertNull(parseRange("bytes=5000-6000", 4096))      // unsatisfiable
        assertNull(parseRange("notarange", 4096))
    }

    // ---- Redact --------------------------------------------------------------

    @Test
    fun `cloudflare style tokens are redacted`() {
        val token = "cloudflared service install eyJhIjoiSGVsbG8sX1dvcmxkIV9IZXJlIiwidCI6IjEyMzQ1Njc4OTAxMjM0NTY3ODkwIn0"
        val out = Redact.apply(token)
        assertFalse(out.contains("SGVsbG8sX1dvcmxk"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `bearer headers are redacted`() {
        val out = Redact.apply("Authorization: Bearer supersecrettoken123456")
        assertFalse(out.contains("supersecrettoken123456"))
        assertTrue(out.contains("Bearer [redacted]"))
    }

    @Test
    fun `normal log lines pass through untouched`() {
        val line = "INFO engine serving on 0.0.0.0:8080"
        assertEquals(line, Redact.apply(line))
    }
}
