package app.stratos.host.security

import java.io.File

/**
 * Detects the real website root inside an extracted directory:
 *  1. index.html/.htm at the top level
 *  2. exactly one top-level folder containing an index
 *  3. public/index.html/.htm
 *  4. otherwise the directory itself (hasIndex = false)
 *
 * Mirrors RootDetect in the Go engine (rootdetect.go).
 */
object RootDetect {

    private val INDEX_NAMES = listOf("index.html", "index.htm")

    fun detect(dir: File): Pair<String, Boolean> {
        if (hasIndex(dir)) return "." to true

        val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name } ?: return "." to false

        if (dirs.size == 1 && hasIndex(dirs[0])) return dirs[0].name to true

        val public = File(dir, "public")
        if (hasIndex(public)) return "public" to true

        if (dirs.size == 1) return dirs[0].name to false
        return "." to false
    }

    fun hasIndex(dir: File): Boolean =
        INDEX_NAMES.any { val f = File(dir, it); f.isFile }
}
