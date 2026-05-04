package dev.gvart.genesara.api.internal.security.jwt

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.account.PlayerLookup
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtDecoderFilterTest {

    private val secret = "test-secret-must-be-at-least-32-bytes-long-1234"
    private val now = Instant.parse("2026-05-02T00:00:00Z")
    private val player = Player(
        id = PlayerId(UUID.randomUUID()),
        username = "alice",
        apiToken = "plr_token",
    )
    private val issuer = JwtIssuer(
        JwtProperties(secret = secret, ttl = Duration.ofHours(1)),
        Clock.fixed(now, ZoneOffset.UTC),
    )
    private val lookup = StubLookup(player)
    private val filter = JwtDecoderFilter(issuer, lookup)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `populates context for a valid JWT and leaves it set for downstream`() {
        val token = issuer.issue(player.id)
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        val captured = chain.snapshot ?: error("chain saw no auth")
        assertEquals(player, captured.principal)
        assertTrue(captured.authorities.any { it.authority == "ROLE_PLAYER" })
        assertEquals(player, SecurityContextHolder.getContext().authentication!!.principal)
    }

    @Test
    fun `passes through with no auth when header is missing`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `passes through with no auth when token is malformed`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer not-a-token") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `passes through with no auth when player no longer exists`() {
        val token = issuer.issue(PlayerId(UUID.randomUUID()))
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    private class StubLookup(private val player: Player) : PlayerLookup {
        override fun find(id: PlayerId): Player? = if (id == player.id) player else null
        override fun findByUsername(username: String): Player? = null
        override fun findByApiToken(token: String): Player? = null
    }

    private class CapturingChain : FilterChain {
        var snapshot: Snapshot? = null
        override fun doFilter(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
            val auth = SecurityContextHolder.getContext().authentication ?: return
            snapshot = Snapshot(auth.principal as Player, auth.authorities.toList())
        }
        data class Snapshot(val principal: Player, val authorities: List<org.springframework.security.core.GrantedAuthority>)
    }
}
