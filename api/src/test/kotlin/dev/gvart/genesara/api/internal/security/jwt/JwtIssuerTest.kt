package dev.gvart.genesara.api.internal.security.jwt

import dev.gvart.genesara.account.PlayerId
import io.jsonwebtoken.JwtException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class JwtIssuerTest {

    private val secret = "test-secret-must-be-at-least-32-bytes-long-1234"
    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `parseSubject round-trips the player id`() {
        val issuer = issuerAt(Instant.parse("2026-05-02T00:00:00Z"))
        val token = issuer.issue(playerId)

        assertEquals(playerId, issuer.parseSubject(token))
    }

    @Test
    fun `expired token cannot be parsed`() {
        val pastIssuer = issuerAt(Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMinutes(1))
        val token = pastIssuer.issue(playerId)
        val nowIssuer = issuerAt(Instant.parse("2026-05-02T00:00:00Z"))

        assertThrows<JwtException> { nowIssuer.parseSubject(token) }
    }

    @Test
    fun `token signed with a different secret is rejected`() {
        val issuerA = issuerAt(Instant.parse("2026-05-02T00:00:00Z"))
        val token = issuerA.issue(playerId)
        val issuerB = JwtIssuer(
            JwtProperties(secret = "different-secret-must-be-at-least-32-bytes-long-X", ttl = Duration.ofHours(1)),
            Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
        )

        assertThrows<JwtException> { issuerB.parseSubject(token) }
    }

    @Test
    fun `construction fails when the secret is shorter than 32 bytes`() {
        assertThrows<IllegalArgumentException> {
            JwtIssuer(
                JwtProperties(secret = "too-short", ttl = Duration.ofHours(1)),
                Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
            )
        }
    }

    @Test
    fun `malformed token is rejected`() {
        val issuer = issuerAt(Instant.parse("2026-05-02T00:00:00Z"))

        assertThrows<JwtException> { issuer.parseSubject("not.a.jwt") }
    }

    private fun issuerAt(now: Instant, ttl: Duration = Duration.ofHours(1)): JwtIssuer =
        JwtIssuer(
            JwtProperties(secret = secret, ttl = ttl),
            Clock.fixed(now, ZoneOffset.UTC),
        )
}
