package app.stratos.host.engine

import app.stratos.host.security.ZipSecurity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin fallback engine on NanoHTTPD. Feature-complete for static
 * serving: GET/HEAD, index resolution, SPA fallback, ETag/Last-Modified/304,
 * gzip, single byte-ranges, branded 404/405 and per-request path confinement.
 *
 * The Go engine (when bundled via gomobile) is the high-performance path;
 * this one exists so the project runs out of the box and as an independent
 * second implementation of the serving contract.
 */
class NanoEngine : EngineApi {

    private val mutex = Mutex()
    private var server: Inner? = null
    private var sites: List<EngineSite> = emptyList()
    private val metrics = NanoMetrics()
    private val access = AccessLog()

    override val kind = EngineKind.KOTLIN
    override val version = "kotlin/1.0.0"

    override suspend fun start(bindHost: String, port: Int, sites: List<EngineSite>, auth: AuthConfig?): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    server?.stop()
                    val host = bindHost.ifBlank { "0.0.0.0" }
                    val s = Inner(host, port, sites.filter { it.enabled }, metrics, access, auth)
                    s.start(5000, true)
                    server = s
                    this@NanoEngine.sites = sites.filter { it.enabled }
                    access.clear() // fresh ring per start, mirroring the Go engine
                    "$host:$port"
                }
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            server?.stop()
            server = null
            metrics.markStopped()
        }
    }

    override suspend fun status(): EngineStatus? = withContext(Dispatchers.IO) {
        val running = server != null
        if (!running) {
            EngineStatus(running = false)
        } else {
            val s = server ?: return@withContext null
            EngineStatus(
                running = true,
                addr = s.hostname + ":" + s.listeningPort,
                requests = metrics.requests.get(),
                active = metrics.active.get(),
                bytesOut = metrics.bytesOut.get(),
                byStatus = metrics.byStatusSnapshot(),
                p50Ms = metrics.p50,
                p95Ms = metrics.p95,
                maxMs = metrics.maxMs,
                uptimeSec = metrics.uptimeSec(),
                sites = sites.size,
                defaultSite = sites.firstOrNull { it.isDefault }?.id ?: "",
                topPaths = metrics.topPaths(8)
            )
        }
    }

    override suspend fun setSites(sites: List<EngineSite>, auth: AuthConfig?) {
        mutex.withLock {
            this.sites = sites.filter { it.enabled }
            server?.replaceSites(this.sites)
            server?.replaceAuth(auth)
        }
    }

    override suspend fun resetMetrics() {
        metrics.reset()
        access.clear()
    }

    override suspend fun accessLog(n: Int): List<AccessEntry> = withContext(Dispatchers.IO) {
        if (server == null) emptyList()
        else access.recent(n.coerceIn(1, 128)).map {
            AccessEntry(at = it.at, method = it.method, path = it.path, status = it.status, ms = it.ms)
        }
    }

    override suspend fun extractZip(zipPath: String, destDir: String): Result<ZipSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val r = ZipSecurity.validateAndExtract(File(zipPath), File(destDir))
                ZipSummary(
                    files = r.files, dirs = r.dirs, bytes = r.bytes,
                    root = r.root, hasIndex = r.hasIndex, entryCount = r.entryCount
                )
            }
        }

    // ---- serving ------------------------------------------------------------

    private class Inner(
        host: String,
        port: Int,
        sites: List<EngineSite>,
        val metrics: NanoMetrics,
        val access: AccessLog,
        auth: AuthConfig?
    ) : NanoHTTPD(host, port) {

        private val lock = Any()
        private var registry: List<EngineSite> = sites
        private var def: EngineSite? = sites.firstOrNull { it.isDefault }

        // Whole-server Basic-auth wall (empty user = disabled).
        private var authUser: String = auth?.user?.trim().orEmpty()
        private var authPass: String = auth?.pass.orEmpty()

        fun replaceSites(sites: List<EngineSite>) = synchronized(lock) {
            registry = sites
            def = sites.firstOrNull { it.isDefault }
        }

        fun replaceAuth(auth: AuthConfig?) = synchronized(lock) {
            authUser = auth?.user?.trim().orEmpty()
            authPass = auth?.pass.orEmpty()
        }

        override fun serve(session: IHTTPSession): Response {
            val started = System.nanoTime()
            metrics.active.incrementAndGet()
            try {
                val denied = checkAuth(session, started)
                if (denied != null) return denied
                metrics.notePath(session.uri ?: "/")
                val resp = route(session, started)
                // Live access log: one entry per served request. 401s are
                // dropped inside AccessLog by policy, and checkAuth already
                // returned above, so only real answers land here.
                access.note(
                    method = session.method.name,
                    rawPath = session.uri ?: "/",
                    status = resp.status.requestStatus,
                    ms = (System.nanoTime() - started) / 1_000_000
                )
                return resp
            } finally {
                metrics.active.decrementAndGet()
            }
        }

        /**
         * Optional HTTP Basic wall. Returns null to proceed, or a ready-made
         * 401 response with the challenge header. Comparisons are constant-time.
         */
        private fun checkAuth(session: IHTTPSession, started: Long): Response? {
            val (user, pass) = synchronized(lock) { authUser to authPass }
            if (user.isEmpty()) return null
            val header = session.headers["authorization"] ?: ""
            val provided = runCatching {
                if (!header.startsWith("Basic ", ignoreCase = true)) null
                else String(
                    java.util.Base64.getDecoder().decode(header.substring(6).trim()),
                    Charsets.UTF_8
                )
            }.getOrNull() ?: ""
            val sep = provided.indexOf(':')
            val gotUser = if (sep < 0) provided else provided.substring(0, sep)
            val gotPass = if (sep < 0) "" else provided.substring(sep + 1)
            val userOk = MessageDigest.isEqual(
                gotUser.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8)
            )
            val passOk = MessageDigest.isEqual(
                gotPass.toByteArray(Charsets.UTF_8), pass.toByteArray(Charsets.UTF_8)
            )
            if (userOk && passOk) return null

            metrics.record(401, started, 0)
            val resp = respondPage(
                Status.UNAUTHORIZED, session, "401 — Unauthorized",
                "This server is protected. Enter the username and password set in Stratos."
            )
            resp.addHeader("WWW-Authenticate", "Basic realm=\"Stratos\", charset=\"UTF-8\"")
            resp.addHeader("Cache-Control", "no-cache")
            return resp
        }

        private fun route(session: IHTTPSession, started: Long): Response {
            val method = session.method
            if (method != Method.GET && method != Method.HEAD) {
                return respondPage(
                    Status.METHOD_NOT_ALLOWED, session, "405 — Method Not Allowed",
                    "This server speaks GET and HEAD only."
                ).also { addAllow(it); metrics.record(405, started, 0) }
            }

            val uri = session.uri ?: "/"
            val site: EngineSite?
            val rel: String
            val secondary = SECONDARY_REGEX.find(uri)
            if (secondary != null) {
                site = synchronized(lock) { registry.firstOrNull { it.id == secondary.groupValues[1] } }
                rel = secondary.groupValues[2].ifBlank { "/" }
                if (site == null) {
                    return notFound(session, null, started, "This site is not hosted (disabled or removed).")
                }
            } else {
                site = synchronized(lock) { def }
                rel = uri
                if (site == null) {
                    // Welcome page: engine alive, no default site.
                    metrics.record(200, started, 0)
                    return newChunkedResponse(Status.OK, "text/html; charset=utf-8",
                        ByteArrayInputStream(welcomePage(session.headers["host"] ?: "").toByteArray()))
                }
            }

            return serveFile(session, site, rel, started)
        }

        private fun serveFile(session: IHTTPSession, site: EngineSite, relIn: String, started: Long): Response {
            val rootDir = File(site.root)
            val rel = normalize(relIn)
                ?: return notFound(session, site, started, "Malformed path.")
            var f = resolveConfined(rootDir, rel)
                ?: return notFound(session, site, started, "Malformed path.")

            if (f.isDirectory) {
                // Bare directory → trailing-slash form so relative links
                // (auto-index entries and relative pages) resolve correctly.
                if (rel != "." && !relIn.endsWith("/")) {
                    metrics.record(301, started, 0)
                    return newFixedLengthResponse(Status.REDIRECT, "text/plain", "")
                        .apply {
                            addHeader("Location", "$relIn/")
                            addHeader("Cache-Control", "no-cache")
                        }
                }
                val idx = sequenceOf("index.html", "index.htm")
                    .map { File(f, it) }.firstOrNull { it.isFile }
                if (idx != null) {
                    f = idx
                } else {
                    // SPA: fall back to the site-root index for client routing.
                    val home = if (site.spa) {
                        resolveConfined(rootDir, "index.html")?.takeIf { it.isFile }
                    } else null
                    if (home != null) {
                        f = home
                    } else {
                        // No index anywhere → branded auto-index of the directory.
                        return dirListing(session, f, rel, started)
                    }
                }
            }

            if (!f.isFile) {
                if (site.spa && !rel.contains('.')) {
                    val home = resolveConfined(rootDir, "index.html")
                    if (home != null && home.isFile) f = home else
                        return notFound(session, site, started, "The page you requested does not exist on this server.")
                } else {
                    return notFound(session, site, started, "The page you requested does not exist on this server.")
                }
            }

            val size = f.length()
            val mtime = f.lastModified() / 1000
            val etag = "\"%x-%x\"".format(size, mtime)
            val ct = mimeByExt(f.extension)

            if (session.headers["if-none-match"] == etag) {
                metrics.record(304, started, 0)
                return newFixedLengthResponse(Status.NOT_MODIFIED, ct, "")
            }
            val ims = session.headers["if-modified-since"]
            if (ims != null) {
                parseHttpDate(ims)?.let { if (mtime / 1000 * 1000 <= it) {
                    metrics.record(304, started, 0)
                    return newFixedLengthResponse(Status.NOT_MODIFIED, ct, "")
                } }
            }

            val resp: Response
            val rangeHeader = session.headers["range"]
            if (rangeHeader != null && size > 0) {
                val range = parseRange(rangeHeader, size)
                if (range != null) {
                    val (from, to) = range
                    resp = newFixedLengthResponse(
                        Status.PARTIAL_CONTENT, ct,
                        RandomAccessInputStream(f, from), to - from + 1
                    )
                    resp.addHeader("Content-Range", "bytes $from-$to/$size")
                    metrics.record(206, started, to - from + 1)
                } else {
                    resp = fullResponse(session, f, ct)
                    metrics.record(200, started, size)
                }
            } else {
                resp = fullResponse(session, f, ct)
                metrics.record(200, started, size)
            }

            resp.addHeader("ETag", etag)
            resp.addHeader("Last-Modified", httpDate(f.lastModified()))
            resp.addHeader("Accept-Ranges", "bytes")
            resp.addHeader("X-Content-Type-Options", "nosniff")
            resp.addHeader("Referrer-Policy", "strict-origin-when-cross-origin")
            if (ct.startsWith("text/html")) {
                resp.addHeader("Cache-Control", "no-cache")
            } else {
                resp.addHeader("Cache-Control", "public, max-age=3600")
            }
            return resp
        }

        /**
         * Branded auto-index for a directory without an index page. The
         * directory is the already-confined [dir]; entries sort directories
         * first; names are HTML- and URL-escaped by [dirListingHtml].
         */
        private fun dirListing(session: IHTTPSession, dir: File, rel: String, started: Long): Response {
            val rows = (dir.listFiles() ?: emptyArray<File>())
                .sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
                .map {
                    ListingRow(
                        name = it.name,
                        isDir = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.length(),
                        mtimeMs = it.lastModified()
                    )
                }
            val html = dirListingHtml(rel, rows)
            metrics.record(200, started, html.toByteArray(Charsets.UTF_8).size.toLong())
            if (session.method == Method.HEAD) {
                return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", "")
            }
            return newChunkedResponse(
                Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            ).apply {
                addHeader("Cache-Control", "no-cache")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "strict-origin-when-cross-origin")
            }
        }

        private fun fullResponse(session: IHTTPSession, f: File, ct: String): Response {
            val size = f.length()
            return if (methodSupportsBody(session) &&
                size in 201..(10 shl 20) &&
                compressible(ct) &&
                (session.headers["accept-encoding"] ?: "").contains("gzip", ignoreCase = true)
            ) {
                val baos = ByteArrayOutputStream(size.toInt())
                GZIPOutputStream(baos).use { gz -> f.inputStream().use { it.copyTo(gz, 64 * 1024) } }
                val gzipped = baos.toByteArray()
                newFixedLengthResponse(Status.OK, ct, ByteArrayInputStream(gzipped), gzipped.size.toLong())
                    .also { it.addHeader("Content-Encoding", "gzip") }
            } else {
                newFixedLengthResponse(Status.OK, ct, FileInputStream(f), size)
            }
        }

        private fun methodSupportsBody(session: IHTTPSession) = session.method == Method.GET

        private fun addAllow(resp: Response) {
            resp.addHeader("Allow", "GET, HEAD")
        }

        private fun notFound(session: IHTTPSession, site: EngineSite?, started: Long, msg: String): Response {
            // The site ships its own 404.html → serve it with a real 404.
            if (site != null) {
                val custom = resolveConfined(File(site.root), "404.html")
                if (custom != null && custom.isFile) {
                    metrics.record(404, started, custom.length())
                    val resp = if (session.method == Method.HEAD) {
                        newFixedLengthResponse(Status.NOT_FOUND, "text/html; charset=utf-8", "")
                    } else {
                        newFixedLengthResponse(
                            Status.NOT_FOUND, "text/html; charset=utf-8",
                            FileInputStream(custom), custom.length()
                        )
                    }
                    resp.addHeader("Cache-Control", "no-cache")
                    resp.addHeader("X-Content-Type-Options", "nosniff")
                    return resp
                }
            }
            metrics.record(404, started, 0)
            return respondPage(Status.NOT_FOUND, session, "404 — Not Found", msg)
        }

        private fun respondPage(status: Status, session: IHTTPSession, title: String, msg: String): Response {
            if (session.method == Method.HEAD) {
                return newFixedLengthResponse(status, "text/html; charset=utf-8", "")
            }
            return newChunkedResponse(status, "text/html; charset=utf-8",
                ByteArrayInputStream(errorPageHtml(status.requestStatus, title, msg).toByteArray()))
        }

        companion object {
            val SECONDARY_REGEX = Regex("^/s/([^/]+)(/.*)?$")
        }
    }
}

/** Bounded, lock-free-ish metrics for the fallback engine. */
internal class NanoMetrics {
    val requests = java.util.concurrent.atomic.AtomicLong()
    val active = java.util.concurrent.atomic.AtomicLong()
    val bytesOut = java.util.concurrent.atomic.AtomicLong()
    private val statusMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val latencies = ArrayDeque<Long>()
    private val latLock = Any()
    private var startedAt = System.currentTimeMillis()

    fun record(status: Int, startedNanos: Long, bytes: Long) {
        requests.incrementAndGet()
        bytesOut.addAndGet(bytes)
        statusMap.merge(status.toString(), 1, Long::plus)
        val ms = (System.nanoTime() - startedNanos) / 1_000_000
        synchronized(latLock) {
            if (latencies.size >= 8192) latencies.removeFirst()
            latencies.addLast(ms)
        }
    }

    fun markStopped() { startedAt = System.currentTimeMillis() }

    // ---- per-path counters (bounded) ----------------------------------------

    private val pathMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Counts one request toward a URL path; bounded at 64 tracked paths. */
    fun notePath(rawPath: String) {
        val p = if (rawPath.length > 128) rawPath.take(128) else rawPath
        if (pathMap.size >= 64 && !pathMap.containsKey(p)) {
            pathMap.entries.minByOrNull { it.value }?.let { pathMap.remove(it.key) }
        }
        pathMap.merge(p, 1L, Long::plus)
    }

    /** Up to [n] hottest paths, ties broken alphabetically for stability. */
    fun topPaths(n: Int): List<TopPath> = pathMap.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(n)
        .map { TopPath(it.key, it.value) }

    fun byStatusSnapshot(): Map<String, Int> =
        statusMap.entries.associate { it.key to it.value.toInt() }

    val p50: Double get() = percentile(0.50)
    val p95: Double get() = percentile(0.95)
    val maxMs: Double get() = synchronized(latLock) { latencies.maxOrNull() ?: 0.0 }.toDouble()

    private fun percentile(q: Double): Double = synchronized(latLock) {
        if (latencies.isEmpty()) 0.0
        else {
            val sorted = latencies.toMutableList()
            sorted.sort()
            sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)].toDouble()
        }
    }

    fun uptimeSec(): Double = if (requests.get() == 0L) 0.0
        else (System.currentTimeMillis() - startedAt) / 1000.0

    fun reset() {
        requests.set(0); bytesOut.set(0); statusMap.clear()
        pathMap.clear()
        synchronized(latLock) { latencies.clear() }
        startedAt = System.currentTimeMillis()
    }
}

/** Streams a byte range of a file without loading it into memory. */
internal class RandomAccessInputStream(private val file: File, private val offset: Long) :
    java.io.InputStream() {
    private val raf = RandomAccessFile(file, "r").apply { seek(offset) }

    override fun read(): Int = raf.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
    override fun close() { raf.close() }
}
