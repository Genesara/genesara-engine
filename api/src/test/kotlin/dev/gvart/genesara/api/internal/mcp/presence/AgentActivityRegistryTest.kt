package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentActivityRegistryTest {

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val registry = AgentActivityRegistry(clock)
    private val a = AgentId(UUID.randomUUID())
    private val b = AgentId(UUID.randomUUID())

    @Test
    fun `staleAgents returns agents not touched since cutoff`() {
        registry.touch(a)
        clock += 20
        val cutoff = clock.instant()
        clock += 20
        registry.touch(b)

        assertEquals(listOf(a), registry.staleAgents(cutoff))
    }

    @Test
    fun `staleAgents is empty when all touches are after cutoff`() {
        registry.touch(a)
        registry.touch(b)

        assertTrue(registry.staleAgents(clock.instant().minusSeconds(60)).isEmpty())
    }

    @Test
    fun `forget removes the agent from staleness consideration`() {
        registry.touch(a)
        registry.forget(a)

        assertTrue(registry.staleAgents(clock.instant().plusSeconds(60)).isEmpty())
    }

    @Test
    fun `touch is idempotent and refreshes timestamp`() {
        registry.touch(a)
        clock += 20
        val cutoffBefore = clock.instant()
        registry.touch(a)

        // After re-touch, agent is no longer stale relative to the earlier cutoff
        assertNull(registry.staleAgents(cutoffBefore).firstOrNull())
    }
}

internal class MutableClock(private var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(millis: Long) { now = now.plusMillis(millis) }
    operator fun plusAssign(millis: Long) { advance(millis) }
}
