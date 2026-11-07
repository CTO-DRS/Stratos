package app.stratos.host.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Bridge to the Go engine (`engine.Engine.Command`) via reflection, so the
 * app compiles even when the `engine.aar` has not been built yet. If the
 * class is missing, [isAvailable] is false and the factory falls back to the
 * pure-Kotlin NanoHTTPD engine.
 */
class GoEngine private constructor(
    private val instance: Any,
    private val commandMethod: java.lang.reflect.Method
) : EngineApi {

    private val singleThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stratos-go-engine").apply { isDaemon = true }
    }

    override val kind = EngineKind.GO
    override val version: String =
        runCatching {
            instance.javaClass.getMethod("VersionString").invoke(instance) as? String
        }.getOrNull() ?: "go"

    private suspend fun bus(request: String): String =
        suspendCancellableCoroutine { cont ->
            singleThread.execute {
                try {
                    cont.resume(commandMethod.invoke(instance, request) as String)
                } catch (t: Throwable) {
                    cont.resume("""{"ok":false,"error":"${t.message ?: "engine failure"}"}""")
                }
            }
        }

    private suspend fun call(cmd: String): BusResult = withContext(Dispatchers.IO) {
        val raw = bus(cmd)
        parse(raw)
    }

    override suspend fun start(bindHost: String, port: Int, sites: List<EngineSite>, auth: AuthConfig?): Result<String> {
        val sitesJson = Json.encodeToString(SITES_SERIALIZER, sites)
        val res = call(
            """{"cmd":"start","port":$port,"bind":"$bindHost"${authFragment(auth)},"sites":$sitesJson}"""
        )
        return if (res.ok) Result.success(res.data?.get("addr")?.jsonPrimitive?.content ?: "")
        else Result.failure(IllegalStateException(res.error))
    }

    override suspend fun stop() {
        call("""{"cmd":"stop"}""")
    }

    override suspend fun status(): EngineStatus? {
        val res = call("""{"cmd":"status"}""")
        if (!res.ok) return null
        return runCatching {
            Json.decodeFromJsonElement(EngineStatus.serializer(), res.data!!)
        }.getOrNull()
    }

    override suspend fun setSites(sites: List<EngineSite>, auth: AuthConfig?) {
        val sitesJson = Json.encodeToString(SITES_SERIALIZER, sites)
        call("""{"cmd":"sites.set","sites":$sitesJson${authFragment(auth)}}""")
    }

    override suspend fun resetMetrics() {
        call("""{"cmd":"metrics.reset"}""")
    }

    override suspend fun extractZip(zipPath: String, destDir: String): Result<ZipSummary> {
        val res = call(
            """{"cmd":"zip.extract","extra":{"zipPath":"${jsonEscape(zipPath)}","destDir":"${jsonEscape(destDir)}"}}"""
        )
        return if (res.ok) {
            runCatching {
                Json.decodeFromJsonElement(ZipSummary.serializer(), res.data!!)
            }
        } else {
            Result.failure(IllegalStateException(res.error))
        }
    }

    private data class BusResult(val ok: Boolean, val data: kotlinx.serialization.json.JsonObject?, val error: String)

    private fun parse(raw: String): BusResult = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val ok = obj["ok"]?.jsonPrimitive?.content == "true"
        BusResult(ok, obj["data"]?.jsonObject, obj["error"]?.jsonPrimitive?.content ?: "unknown error")
    }.getOrElse { BusResult(false, null, "bad engine response: $raw") }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /**
     * Bus fragment carrying optional Basic-auth credentials. Blank user or
     * empty pass means disabled — the fragment is omitted entirely so the
     * engine keeps its current behaviour (nil auth on the Go side).
     */
    private fun authFragment(auth: AuthConfig?): String {
        if (auth == null) return ""
        val user = auth.user.trim()
        if (user.isEmpty() || auth.pass.isEmpty()) return ""
        return ",\"auth\":{\"user\":\"" + jsonEscape(user) + "\",\"pass\":\"" + jsonEscape(auth.pass) + "\"}"
    }

    companion object {
        private val SITES_SERIALIZER =
            kotlinx.serialization.builtins.ListSerializer(EngineSite.serializer())

        /** Detects a bundled Go engine without a compile-time dependency. */
        fun create(): GoEngine? = runCatching {
            val cls = Class.forName("engine.Engine")
            val instance = cls.getDeclaredConstructor().newInstance()
            val command = cls.getMethod("Command", String::class.java)
            GoEngine(instance, command)
        }.getOrNull()
    }
}
