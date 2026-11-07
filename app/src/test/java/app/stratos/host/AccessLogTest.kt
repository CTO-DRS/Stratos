package app.stratos.host

import app.stratos.host.engine.AccessLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live access-log ring: bounded capacity, newest-first reads, 401 policy,
 * path truncation and credential redaction — mirroring the Go engine tests.
 */
class AccessLogTest {

    @Test
    fun `ring keeps newest entries and evicts oldest past the cap`() {
        val log = AccessLog(capacity = 8)
        repeat(12) { i ->
            log.note("GET", "/p$i", 200, 5, nowMs = i.toLong())
        }
        val recent = log.recent(8)
        assertEquals(8, recent.size)
        assertEquals("/p11", recent[0].path) // newest first
        assertEquals("/p4", recent[7].path)  // oldest surviving
        // Asking for more than the ring holds never over-reports.
        assertEquals(8, log.recent(500).size)
    }

    @Test
    fun `recent returns newest-first in bounded batches`() {
        val log = AccessLog(capacity = 10)
        repeat(6) { i -> log.note("GET", "/$i", 200, 1, nowMs = i.toLong()) }
        val top3 = log.recent(3)
        assertEquals(listOf("/5", "/4", "/3"), top3.map { it.path })
    }

    @Test
    fun `auth-rejected requests are never recorded`() {
        val log = AccessLog()
        log.note("GET", "/", 401, 2)
        log.note("GET", "/", 200, 2)
        assertEquals(1, log.recent(10).size)
        assertEquals(200, log.recent(10)[0].status)
    }

    @Test
    fun `long paths are truncated and tokens redacted`() {
        val log = AccessLog()
        val long = "/" + "a".repeat(300)
        log.note("GET", long, 200, 1, nowMs = 1)
        log.note("GET", "/x?token=eyJaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbb.cccc", 200, 1, nowMs = 2)
        val rows = log.recent(2)
        // Newest first: the token request was noted last → rows[0].
        assertTrue(rows[0].path.contains("[redacted]"))
        assertFalse(rows[0].path.contains("eyJaaaaaaaa"))
        assertTrue(rows[1].path.length <= 128)
    }

    @Test
    fun `clear empties the ring`() {
        val log = AccessLog()
        log.note("GET", "/", 200, 1)
        log.clear()
        assertTrue(log.recent(10).isEmpty())
    }
}
