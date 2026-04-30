package dev.gvart.genesara.api.internal.mcp.tools.getmap

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.RecalledNode
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetMapToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach
    fun setUp() {
        AgentContextHolder.set(agentId)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    @Test
    fun `returns an empty list for a fresh agent who has never looked around`() {
        val tool = GetMapTool(StubMapMemory(emptyList()), activity)

        val response = tool.invoke(GetMapRequest(), toolContext)

        assertEquals(emptyList(), response.nodes)
    }

    @Test
    fun `projects every recalled node into a RecalledNodeView`() {
        val recalled = listOf(
            RecalledNode(
                nodeId = NodeId(11L),
                regionId = RegionId(1L),
                q = 0,
                r = 0,
                terrain = Terrain.FOREST,
                biome = Biome.FOREST,
                firstSeenTick = 5L,
                lastSeenTick = 12L,
            ),
            RecalledNode(
                nodeId = NodeId(22L),
                regionId = RegionId(1L),
                q = 1,
                r = 0,
                terrain = Terrain.PLAINS,
                biome = null,
                firstSeenTick = 5L,
                lastSeenTick = 5L,
            ),
        )
        val tool = GetMapTool(StubMapMemory(recalled), activity)

        val response = tool.invoke(GetMapRequest(), toolContext)

        assertEquals(2, response.nodes.size)
        val first = response.nodes[0]
        assertEquals(11L, first.nodeId)
        assertEquals("FOREST", first.terrain)
        assertEquals("FOREST", first.biome)
        assertEquals(5L, first.firstSeenTick)
        assertEquals(12L, first.lastSeenTick)
        // Null biome (unpainted region) projects to null in the view rather than throwing.
        assertEquals(null, response.nodes[1].biome)
    }

    @Test
    fun `every invocation touches the activity registry`() {
        val tool = GetMapTool(StubMapMemory(emptyList()), activity)

        tool.invoke(GetMapRequest(), toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private class StubMapMemory(private val recall: List<RecalledNode>) : AgentMapMemoryGateway {
        override fun recordVisible(
            agentId: AgentId,
            updates: Collection<NodeMemoryUpdate>,
            tick: Long,
        ) {
            // GetMapTool is read-only — recordVisible never fires through it.
            error("GetMapTool must not call recordVisible")
        }
        override fun recall(agentId: AgentId): List<RecalledNode> = recall
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
