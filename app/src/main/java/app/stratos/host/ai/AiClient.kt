package app.stratos.host.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat-completions client for the AI site builder.
 * Works with Z.ai GLM, OpenAI, OpenRouter or any compatible endpoint.
 * The API key is provided as a CharArray and never logged.
 */
class AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Blocking non-streaming call (simpler and robust behind slow proxies);
     * invoked from a coroutine on Dispatchers.IO.
     *
     * @return the assistant message content.
     */
    suspend fun complete(
        endpoint: String,
        apiKey: CharArray,
        model: String,
        system: String,
        user: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.7)
                put("max_tokens", 16384)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }.toString()

            val url = endpoint.trimEnd('/') + "/chat/completions"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + String(apiKey))
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${text.take(300)}")
                }
                val json = JSONObject(text)
                val content = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                stripFences(content)
            }
        }
    }

    /** Removes markdown fences if a model adds them despite instructions. */
    private fun stripFences(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```html").removePrefix("```")
        val idx = withoutOpen.lastIndexOf("```")
        return (if (idx >= 0) withoutOpen.substring(0, idx) else withoutOpen).trim()
    }
}
