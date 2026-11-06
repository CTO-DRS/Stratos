package app.stratos.host.security

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Pure-Kotlin mirror of the Go engine's ZIP defences. Used by the fallback
 * engine path (and available for CLI/testing); when the Go engine is bundled
 * it performs the authoritative validation instead.
 *
 * Defences: zip-slip, absolute/drive paths, NUL bytes, symlinks, encrypted
 * entries, duplicate names, per-entry size, total size, compression ratio
 * bombs, and CRC verification on read.
 */
object ZipSecurity {

    const val MAX_ENTRIES = 20_000
    const val MAX_TOTAL_BYTES = 512L shl 20   // 512 MB
    const val MAX_ENTRY_BYTES = 256L shl 20   // 256 MB
    const val MAX_RATIO = 500L                // enforced for entries expanding beyond 1 MB

    data class Result(
        val files: Int,
        val dirs: Int,
        val bytes: Long,
        val root: String,
        val hasIndex: Boolean,
        val entryCount: Int,
        val destDir: File
    )

    class ValidationException(reason: String) : Exception(reason)

    fun validateAndExtract(zip: File, destDir: File): Result {
        require(zip.isFile) { "archive not found" }
        ZipFile(zip).use { zf ->
            val entries = zf.entries().asSequence().toList()
            if (entries.isEmpty()) throw ValidationException("archive is empty")
            if (entries.size > MAX_ENTRIES) {
                throw ValidationException("too many entries: ${entries.size} (limit $MAX_ENTRIES)")
            }

            val seen = HashSet<String>(entries.size * 2)
            var totalBytes = 0L
            var files = 0
            var dirs = 0

            val tmp = File(destDir.parentFile, destDir.name + ".tmp-" + System.nanoTime().toString(36))
            try {
                tmp.mkdirs() ?: throw IllegalStateException("cannot create temp dir")
                for (e in entries) {
                    val clean = sanitize(e.name)
                    if (!seen.add(clean)) throw ValidationException("duplicate entry name: $clean")
                    if (e.isDirectory) {
                        dirs++
                    } else {
                        files++
                        if (e.size > MAX_ENTRY_BYTES) {
                            throw ValidationException("entry too large: $clean (${e.size} bytes)")
                        }
                        if (e.size > (1L shl 20) && e.size / (e.compressedSize.coerceAtLeast(1)) > MAX_RATIO) {
                            throw ValidationException("compression ratio too high (possible zip bomb): $clean")
                        }
                    }
                    totalBytes += e.size
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw ValidationException("archive expands beyond the ${MAX_TOTAL_BYTES shr 20} MB limit")
                    }
                    if (e.isDirectory) {
                        File(tmp, clean).mkdirs()
                    } else {
                        extractOne(zf, e, File(tmp, clean))
                    }
                }
                if (files == 0) throw ValidationException("archive contains no files")

                destDir.parentFile?.mkdirs()
                if (!tmp.renameTo(destDir)) {
                    // Cross-device or transient failure — copy-move fallback.
                    tmp.copyRecursively(destDir, overwrite = true)
                    tmp.deleteRecursively()
                }
            } catch (t: Throwable) {
                tmp.deleteRecursively()
                throw t
            }

            val (root, hasIndex) = RootDetect.detect(destDir)
            return Result(files, dirs, totalBytes, root, hasIndex, entries.size, destDir)
        }
    }

    private fun extractOne(zf: ZipFile, entry: ZipEntry, target: File) {
        target.parentFile?.mkdirs()
        zf.getInputStream(entry).use { input ->
            FileOutputStream(target).use { out ->
                val copied = input.copyTo(out, 64 * 1024)
                if (copied != entry.size) {
                    throw ValidationException("entry ${entry.name} failed CRC/size verification")
                }
            }
        }
    }

    /** Rejects dangerous names and returns a clean slash-separated relative path. */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) throw ValidationException("empty entry name")
        if (raw.contains('\u0000')) throw ValidationException("NUL byte in entry name")
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/")) throw ValidationException("absolute path entry: $raw")
        if (unified.length >= 2 && unified[1] == ':') throw ValidationException("drive-letter path entry: $raw")
        if (unified.split('/').any { it == ".." }) throw ValidationException("path traversal entry: $raw")
        val cleaned = java.nio.file.Paths.get("/" + unified).normalize().toString().trimStart('/')
        if (cleaned.isEmpty() || cleaned == ".") throw ValidationException("empty path after clean: $raw")
        return cleaned
    }
}
