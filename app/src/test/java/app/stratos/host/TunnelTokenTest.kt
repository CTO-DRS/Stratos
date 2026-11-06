package app.stratos.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.stratos.host.tunnel.CustomDomain
import app.stratos.host.tunnel.TunnelToken
import java.util.Base64

class TunnelTokenTest {

    private fun encodeUrlSafe(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun fakePayload(tunnelId: String, secret: String) =
        """{"a":"d1f2c3a4_account","t":"$tunnelId","s":"$secret"}"""

    @Test
    fun `decodes tunnel id from standard token`() {
        val id = "f4d2a7c1-9b3e-4c55-8a11-77c2d9e0b123"
        val token = encodeUrlSafe(fakePayload(id, "super-secret-value"))
        assertEquals(id, TunnelToken.decodeTunnelId(token))
    }

    @Test
    fun `decodes tunnel id from full service-install command`() {
        val id = "f4d2a7c1-9b3e-4c55-8a11-77c2d9e0b123"
        val token = encodeUrlSafe(fakePayload(id, "s3cret"))
        assertEquals(
            id,
            TunnelToken.decodeTunnelId("cloudflared service install $token")
        )
    }

    @Test
    fun `never leaks the secret`() {
        val id = "f4d2a7c1-9b3e-4c55-8a11-77c2d9e0b123"
        val secret = "TOPSECRET-SHOULD-NOT-APPEAR"
        val token = encodeUrlSafe(fakePayload(id, secret))
        val out = TunnelToken.decodeTunnelId(token)
        assertEquals(id, out)
        assertFalse(out!!.contains(secret))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(TunnelToken.decodeTunnelId(""))
        assertNull(TunnelToken.decodeTunnelId("not-a-token"))
        assertNull(TunnelToken.decodeTunnelId(Base64.getUrlEncoder().encodeToString("{}".toByteArray())))
        assertNull(
            TunnelToken.decodeTunnelId(
                encodeUrlSafe("""{"a":"acc","t":"not-a-uuid","s":"x"}""")
            )
        )
    }
}

class CustomDomainTest {

    @Test
    fun `normalizes user input`() {
        assertEquals(
            "app.example.com",
            CustomDomain.normalize("  HTTPS://App.Example.com/  ")
        )
        assertEquals("example.com", CustomDomain.normalize("Example.COM."))
    }

    @Test
    fun `valid hostnames`() {
        assertTrue(CustomDomain.isValid("example.com"))
        assertTrue(CustomDomain.isValid("app.example.com"))
        assertTrue(CustomDomain.isValid("my-site.example.co.uk"))
        assertTrue(CustomDomain.isValid("a1.example.com"))
    }

    @Test
    fun `invalid hostnames`() {
        assertFalse(CustomDomain.isValid(""))
        assertFalse(CustomDomain.isValid("-bad.example.com"))
        assertFalse(CustomDomain.isValid("bad-.example.com"))
        assertFalse(CustomDomain.isValid("no dots"))
        assertFalse(CustomDomain.isValid("under_score.example.com"))
        assertFalse(CustomDomain.isValid("app..example.com"))
    }

    @Test
    fun `detects bare domains`() {
        assertTrue(CustomDomain.isBareDomain("example.com"))
        assertFalse(CustomDomain.isBareDomain("app.example.com"))
    }
}
