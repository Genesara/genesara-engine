package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.NodeId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSummaryProjectionTest {

    private val agent = Agent(
        id = AgentId(UUID.randomUUID()),
        owner = PlayerId(UUID.randomUUID()),
        name = "Komar",
        classId = AgentClass.SCOUT,
        race = RaceId("human_steppe"),
        level = 4,
        xpCurrent = 30,
        xpToNext = 400,
    )

    private val body = BodyView(
        hp = 80, maxHp = 100,
        stamina = 60, maxStamina = 100,
        mana = 0, maxMana = 0,
        hunger = 50, maxHunger = 100,
        thirst = 50, maxThirst = 100,
        sleep = 50, maxSleep = 100,
    )

    @Test
    fun `spawned agent populates gauges, location, and spawned flag`() {
        val node = NodeId(42L)

        val summary = projectAgentSummary(
            agent = agent,
            body = body,
            location = node,
            activeNode = node,
            lastActiveAt = Instant.parse("2026-05-02T12:00:00Z"),
        )

        assertEquals(agent.id.id, summary.agentId)
        assertEquals("Komar", summary.name)
        assertEquals("SCOUT", summary.classId)
        assertEquals(4, summary.level)
        val gauges = assertNotNull(summary.gauges)
        assertEquals(80, gauges.hp.current)
        assertEquals(100, gauges.hp.max)
        assertEquals(42L, summary.locationNodeId)
        assertTrue(summary.spawned)
    }

    @Test
    fun `despawned agent shows last-known location with spawned=false`() {
        val node = NodeId(7L)

        val summary = projectAgentSummary(
            agent = agent,
            body = body,
            location = node,
            activeNode = null,
            lastActiveAt = null,
        )

        assertEquals(7L, summary.locationNodeId)
        assertFalse(summary.spawned)
        assertNull(summary.lastActiveAt)
    }

    @Test
    fun `agent that has never spawned has null gauges, null location, spawned=false`() {
        val summary = projectAgentSummary(
            agent = agent,
            body = null,
            location = null,
            activeNode = null,
            lastActiveAt = null,
        )

        assertNull(summary.gauges)
        assertNull(summary.locationNodeId)
        assertFalse(summary.spawned)
    }

    @Test
    fun `classId null when the agent has not been assigned a class`() {
        val unclassed = agent.copy(classId = null)

        val summary = projectAgentSummary(
            agent = unclassed,
            body = null,
            location = null,
            activeNode = null,
            lastActiveAt = null,
        )

        assertNull(summary.classId)
    }
}
