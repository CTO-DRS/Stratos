package app.stratos.host.tunnel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Helpers for Cloudflare named-tunnel tokens and custom hostnames.
 *
 * A Cloudflare tunnel token is a base64url-encoded JSON payload:
 *   {"a":"<account tag>","t":"<tunnel UUID>","s":"<secret>"}
 * Only the tunnel ID ("t") is ever decoded here — it is public data that is
 * also printed in DNS records. The secret ("s") is NEVER extracted, logged
 * or echoed; it stays encrypted in the Vault and is passed to the cloudflared
 * process via the environment only.
 */
object TunnelToken {

    private val json = Json { ignoreUnknownKeys = true }

    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /**
     * Extracts the public tunnel UUID from a Cloudflare tunnel token.
     * Accepts the bare token or the full `cloudflared service install eyJ…`
     * command copied from the dashboard. Returns null when the token cannot
     * be parsed — never throws, never returns the secret.
     */
    fun decodeTunnelId(rawToken: String): String? {
        val token = rawToken
            .trim()
            .removePrefix("cloudflared service install")
            .trim()
            .removeSurrounding("\"")
            .trim()
        if (token.isEmpty()) return null

        val payload = runCatching {
            val normalized = token.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = java.util.Base64.getDecoder().decode(padded)
            String(decoded, Charsets.UTF_8)
        }.getOrNull() ?: return null

        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return null
        val id = listOf("t", "tunnelID", "tunnelId", "id")
            .firstNotNullOfOrNull { key -> obj[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
            ?: return null

        return id.takeIf { UUID_REGEX.matches(it) }
    }
}

/** Custom hostname normalization + validation (RFC 1123 subset). */
object CustomDomain {

    private val HOSTNAME_REGEX = Regex(
        "^(?=.{1,253}\$)(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+\$"
    )

    /** Lowercases, strips scheme/path/whitespace and a trailing dot. */
    fun normalize(input: String): String {
        var s = input.trim()
        val lower = s.lowercase()
        if (lower.startsWith("https://")) s = s.substring(8)
        else if (lower.startsWith("http://")) s = s.substring(7)
        return s.substringBefore('/').trim().trimEnd('.').lowercase()
    }

    fun isValid(hostname: String): Boolean = HOSTNAME_REGEX.matches(hostname)

    /** True when the user entered a bare registrable domain (needs a subdomain decision). */
    fun isBareDomain(hostname: String): Boolean = hostname.count { it == '.' } == 1
}
