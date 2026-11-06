package app.stratos.host.util

import java.util.Locale

/** Display formatting helpers (locale-stable, Arabic-friendly). */
object Formatters {

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    fun ms(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.2f s", v / 1000.0)
        v >= 10 -> String.format(Locale.US, "%.0f ms", v)
        else -> String.format(Locale.US, "%.1f ms", v)
    }

    fun duration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            m > 0 -> String.format(Locale.US, "%d:%02d", m, s)
            else -> "${s}s"
        }
    }

    fun count(n: Long): String = String.format(Locale.US, "%,d", n)
}

/** Credential redaction used by Kotlin-side logs (mirrors the Go engine). */
object Redact {

    private val bareToken = Regex("eyJ[A-Za-z0-9_-]{16,}")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}")
    private val bearer = Regex("(?i)(bearer|token)\\s+([A-Za-z0-9._-]{12,})")

    fun apply(s: String): String = s
        .replace(jwt, "eyJ…[redacted]")
        .replace(bareToken, "eyJ…[redacted]")
        .replace(bearer, "$1 [redacted]")
}
