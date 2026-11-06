package app.stratos.host.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** MIME + serving helpers shared by the Kotlin fallback engine. */

internal val MIME_MAP = mapOf(
    "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
    "css" to "text/css; charset=utf-8", "js" to "text/javascript; charset=utf-8",
    "mjs" to "text/javascript; charset=utf-8", "json" to "application/json",
    "map" to "application/json", "txt" to "text/plain; charset=utf-8",
    "md" to "text/markdown; charset=utf-8", "xml" to "application/xml",
    "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
    "avif" to "image/avif", "ico" to "image/x-icon", "bmp" to "image/bmp",
    "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf",
    "otf" to "font/otf", "eot" to "application/vnd.ms-fontobject",
    "mp4" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
    "m4a" to "audio/mp4", "pdf" to "application/pdf", "zip" to "application/zip",
    "wasm" to "application/wasm", "webmanifest" to "application/manifest+json",
    "csv" to "text/csv; charset=utf-8", "ics" to "text/calendar"
)

fun mimeByExt(ext: String): String =
    MIME_MAP[ext.lowercase(Locale.ROOT)] ?: "application/octet-stream"

fun compressible(ct: String): Boolean = ct.startsWith("text/") ||
    ct.startsWith("application/json") || ct.startsWith("application/javascript") ||
    ct.startsWith("application/xml") || ct == "image/svg+xml" ||
    ct == "application/wasm" || ct == "application/manifest+json"

/** Normalises a URL path to a safe relative path, or null when malformed. */
fun normalize(relIn: String): String? {
    if (relIn.contains('\u0000')) return null
    val decoded = try {
        java.net.URLDecoder.decode(relIn, Charsets.UTF_8.name())
    } catch (_: Exception) {
        return null
    }
    if (decoded.contains('\u0000')) return null
    // fs.ValidPath-equivalent: reject any ".." element (also covers %2e%2e
    // after decoding), mirroring the Go engine's pre-I/O traversal refusal.
    if (decoded.contains("..")) return null
    var p = java.nio.file.Paths.get("/" + decoded.replace('\\', '/')).normalize().toString()
    if (p == "/" || p.isEmpty()) p = "."
    if (p.startsWith("/")) p = p.substring(1)
    if (p.contains("..")) return null
    return p
}

/** Resolves [rel] under [root], refusing any escape beyond the root. */
fun resolveConfined(root: File, rel: String): File? {
    val rootCanon = root.canonicalFile
    val target = File(rootCanon, rel)
    val targetCanon = try {
        target.canonicalFile
    } catch (_: Exception) {
        return null
    }
    if (!targetCanon.path.startsWith(rootCanon.path + File.separator) &&
        targetCanon.path != rootCanon.path
    ) return null
    return targetCanon
}

/** Single "bytes=a-b" range; null for multi/suffix/invalid (falls back to full). */
fun parseRange(header: String, size: Long): Pair<Long, Long>? {
    if (size <= 0 || !header.startsWith("bytes=")) return null
    val spec = header.removePrefix("bytes=")
    if (spec.contains(',')) return null
    val dash = spec.indexOf('-')
    if (dash <= 0) return null
    val a = spec.substring(0, dash).trim()
    val b = spec.substring(dash + 1).trim()
    val from = a.toLongOrNull() ?: return null
    if (from < 0 || from >= size) return null
    val to = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
    if (to < from) return null
    return from to to
}

private val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("GMT")
}

fun httpDate(millis: Long): String = httpDateFormat.format(Date(millis))

fun parseHttpDate(value: String): Long? = try {
    httpDateFormat.parse(value)?.time
} catch (_: Exception) {
    null
}

/** Branded error page (light theme, mirrors the Go engine's design). */
fun errorPageHtml(code: Int, title: String, msg: String): String = """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>$code · Stratos</title>
<style>
:root{color-scheme:light}*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',sans-serif;background:#fafafa;color:#0a0a0a;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{background:#fff;border:1px solid #e4e4e7;border-radius:16px;padding:48px 40px;max-width:440px;width:100%;text-align:center}
.code{font-size:13px;letter-spacing:.08em;color:#71717a;text-transform:uppercase}
h1{font-size:28px;margin:12px 0 8px;font-weight:600}p{color:#52525b;font-size:15px;line-height:1.6}
.brand{margin-top:28px;font-size:13px;color:#a1a1aa}.brand b{color:#0a0a0a;font-weight:600}
</style></head><body><div class="card">
<div class="code">HTTP $code</div><h1>$title</h1><p>$msg</p>
<div class="brand">Served by <b>Stratos</b> · your pocket server</div>
</div></body></html>
"""

/** Branded welcome page when no default site is configured. */
fun welcomePage(host: String): String = """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Stratos is live</title>
<style>
:root{color-scheme:light}*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',sans-serif;background:#fafafa;color:#0a0a0a;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{background:#fff;border:1px solid #e4e4e7;border-radius:16px;padding:56px 44px;max-width:520px;width:100%;text-align:center}
.pill{display:inline-flex;align-items:center;gap:8px;border:1px solid #e4e4e7;border-radius:999px;padding:6px 14px;font-size:13px;color:#52525b}
.live{width:8px;height:8px;border-radius:50%;background:#16a34a;box-shadow:0 0 0 4px rgba(22,163,74,.15)}
h1{font-size:30px;margin:22px 0 10px;font-weight:650;letter-spacing:-.02em}
p{color:#52525b;font-size:15px;line-height:1.65;max-width:40ch;margin:0 auto}
.hint{margin-top:26px;background:#fafafa;border:1px dashed #e4e4e7;border-radius:10px;padding:14px 16px;font-size:13.5px;color:#71717a}
.addr{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#0a0a0a}
.brand{margin-top:30px;font-size:13px;color:#a1a1aa}.brand b{color:#0a0a0a}
</style></head><body><div class="card">
<span class="pill"><span class="live"></span>● Server online</span>
<h1>Stratos is live.</h1>
<p>This server is running, but no website has been set as the default yet. Import a site in the Stratos app and it will appear right here.</p>
<div class="hint">Listening on <span class="addr">$host</span></div>
<div class="brand">Powered by <b>Stratos</b> — your pocket server</div>
</div></body></html>
"""

/** Small shared helpers (kept here so the engine layer stays dependency-free). */
object maybeFileHelpers {
    fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }
}
