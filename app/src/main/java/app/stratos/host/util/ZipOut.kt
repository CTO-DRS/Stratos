package app.stratos.host.util

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a site directory out as a UTF-8 ZIP archive (the export path of
 * "your phone, your server, your rules" — data goes back out as easily as it
 * came in). Entry names are relative to the site root, so the archive
 * re-imports into Stratos (or opens anywhere else) with index.html at its
 * top level. Only regular files inside the root are packed; entry paths are
 * canonicalized first, so a stray symlink can never influence the layout.
 */
object ZipOut {

    fun zipDirectory(root: File, out: OutputStream) {
        require(root.isDirectory) { "export root is not a directory: ${root.path}" }
        val basePath = root.canonicalPath + File.separator
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            root.walkTopDown()
                .filter { it.isFile }
                .filter { it.canonicalFile.path.startsWith(basePath) }
                .forEach { f ->
                    val rel = f.canonicalFile.path
                        .removePrefix(basePath)
                        .replace(File.separatorChar, '/')
                    if (rel.isEmpty()) return@forEach
                    val entry = ZipEntry(rel)
                    val mtime = f.lastModified()
                    if (mtime > 0) entry.time = mtime
                    zip.putNextEntry(entry)
                    f.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
        }
    }
}
