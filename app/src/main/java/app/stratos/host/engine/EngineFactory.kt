package app.stratos.host.engine

/**
 * Chooses the engine: Go (via gomobile AAR, high performance) when bundled,
 * otherwise the pure-Kotlin NanoHTTPD fallback. UI and service code are
 * identical for both.
 */
object EngineFactory {

    fun create(): EngineApi = GoEngine.create() ?: NanoEngine()
}
