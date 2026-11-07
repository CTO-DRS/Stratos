package app.stratos.host.engine

import app.stratos.host.util.Redact

/**
 * Bounded ring of recently served requests backing the live access log.
 * Mirrors the Go engine's per-server ring: newest kept, oldest evicted past
 * the cap, 401 challenges never recorded (Basic-auth negotiation would
 * otherwise double-count every visit), paths truncated to 128 chars and
 * credential-redacted before storage — tokens never survive.
 */
class AccessLog(private val capacity: Int = 128) {

    /** One served request, ready for display. */
    data class Entry(
        val at: Long,
        val method: String,
        val path: String,
        val status: Int,
        val ms: Long
    )

    private val lock = Any()
    private val ring = ArrayList<Entry>(capacity.coerceAtLeast(1))

    /** Records one served request. 401s are dropped by policy. */
    fun note(method: String, rawPath: String, status: Int, ms: Long, nowMs: Long = System.currentTimeMillis()) {
        if (status == 401) return
        val p = if (rawPath.length > 128) rawPath.take(128) else rawPath
        val entry = Entry(nowMs, method, Redact.apply(p), status, ms)
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeAt(0)
            ring.add(entry)
        }
    }

    /** Up to [n] recent entries, newest first. */
    fun recent(n: Int): List<Entry> = synchronized(lock) {
        val out = ArrayList<Entry>(n.coerceAtMost(ring.size))
        var i = ring.size - 1
        while (i >= 0 && out.size < n) {
            out.add(ring[i])
            i--
        }
        out
    }

    fun clear() = synchronized(lock) { ring.clear() }
}
