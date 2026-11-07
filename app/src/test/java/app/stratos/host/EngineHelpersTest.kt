package app.stratos.host

import app.stratos.host.engine.ListingRow
import app.stratos.host.engine.dirListingHtml
import app.stratos.host.engine.escapeHtml
import app.stratos.host.engine.normalize
import app.stratos.host.engine.parseRange
import app.stratos.host.engine.pathEscapeSegment
import app.stratos.host.engine.resolveConfined
import app.stratos.host.util.Redact
import app.stratos.host.util.ZipOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

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
        inside.parentFile?.mkdirs()
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

    // ---- directory auto-index ------------------------------------------------

    @Test
    fun `listing escapes names and keeps parent link below root`() {
        val html = dirListingHtml(
            "docs",
            listOf(
                ListingRow("sub", isDir = true, size = 0, mtimeMs = 1_700_000_000_000),
                ListingRow("weird <&>.txt", isDir = false, size = 12, mtimeMs = 1_700_000_000_000)
            )
        )
        assertTrue(html.contains("Index of /docs"))
        assertTrue(html.contains("""href="../""""))
        assertTrue(html.contains("weird &lt;&amp;&gt;.txt"))
        assertFalse(html.contains("weird <&>"))
        // Directory link carries the trailing slash.
        assertTrue(html.contains("""href="sub/">sub/</a>"""))
        // Raw percent-encoded href for the escaped name.
        assertTrue(html.contains("weird%20%3C%26%3E.txt"))
        // Humanized size column present.
        assertTrue(html.contains("12 B"))
    }

    @Test
    fun `root listing has no parent link and escapes the title`() {
        val html = dirListingHtml(
            ".",
            listOf(ListingRow("index.html", isDir = false, size = 5, mtimeMs = 0))
        )
        assertFalse(html.contains("""href="../""""))
        assertTrue(html.contains("Index of /"))
        assertTrue(html.contains("index.html"))
        // mtime of 0 renders as an em dash, never 1970 noise.
        assertTrue(html.contains("<td class=\"dim\">—</td>"))
    }

    @Test
    fun `empty directory renders the empty note`() {
        val html = dirListingHtml("sub", emptyList())
        assertTrue(html.contains("Empty directory."))
    }

    @Test
    fun `escape and pathEscape are injection-safe`() {
        assertEquals("a&amp;b&lt;c&gt;", escapeHtml("a&b<c>"))
        assertEquals("q&quot;uote&#39;s", escapeHtml("q\"uote's"))
        assertEquals("weird%20%3C%26%3E.txt", pathEscapeSegment("weird <&>.txt"))
        assertEquals("plain.txt", pathEscapeSegment("plain.txt"))
        // Non-ASCII bytes are percent-encoded, never emitted raw.
        val enc = pathEscapeSegment("صورة.png")
        assertFalse(enc.contains("ص"))
        assertTrue(enc.startsWith("%D8"))
    }

    // ---- ZipOut (site export) --------------------------------------------------

    @Test
    fun `zipDirectory packs files with relative entries and round-trips`() {
        val root = tmp.newFolder("site")
        File(root, "index.html").writeText("<h1>Hi</h1>")
        val sub = File(root, "assets").apply { mkdirs() }
        File(sub, "style.css").writeText("body{}")

        val out = java.io.ByteArrayOutputStream()
        ZipOut.zipDirectory(root, out)
        assertTrue(out.size() > 0)

        val names = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    names[e.name] = zis.readBytes().decodeToString()
                }
                e = zis.nextEntry
            }
        }
        assertEquals("<h1>Hi</h1>", names["index.html"])
        assertEquals("body{}", names["assets/style.css"])
        assertEquals(2, names.size)
    }

    // ---- Redaction --------------------------------------------------------------

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
