package app.stratos.host.engine

import kotlinx.serialization.Serializable

/** Which concrete engine backs the HTTP serving. */
enum class EngineKind(val id: String) {
    GO("go"),
    KOTLIN("kotlin");

    companion object {
        fun from(id: String?) = if (id == GO.id) GO else KOTLIN
    }
}

/** One hosted site as seen by the engine. */
@Serializable
data class EngineSite(
    val id: String,
    val name: String = "",
    val root: String,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val spa: Boolean = false,
    val cacheMax: Int = 0
)

/** Live engine status/metrics snapshot (same fields the Go bus reports). */
@Serializable
data class EngineStatus(
    val running: Boolean = false,
    val addr: String = "",
    val requests: Long = 0,
    val active: Long = 0,
    val bytesOut: Long = 0,
    val byStatus: Map<String, Int> = emptyMap(),
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val maxMs: Double = 0.0,
    val uptimeSec: Double = 0.0,
    val sites: Int = 0,
    val defaultSite: String = ""
)

/** Result summary of a validated ZIP extraction. */
@Serializable
data class ZipSummary(
    val files: Int = 0,
    val dirs: Int = 0,
    val bytes: Long = 0,
    val root: String = ".",
    val hasIndex: Boolean = false,
    val entryCount: Int = 0
)

/** The single control surface both engines implement. UI never knows which one runs. */
interface EngineApi {
    val kind: EngineKind
    val version: String

    /** @return the listening address, e.g. "0.0.0.0:8080". */
    suspend fun start(bindHost: String, port: Int, sites: List<EngineSite>): Result<String>

    suspend fun stop()

    suspend fun status(): EngineStatus?

    suspend fun setSites(sites: List<EngineSite>)

    suspend fun resetMetrics()

    /** Validates (zip-slip, symlinks, bombs, CRC) then atomically extracts. */
    suspend fun extractZip(zipPath: String, destDir: String): Result<ZipSummary>
}
