package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.UnequipResult
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

class UnequipSlotToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `unequipped result returns kind=unequipped with the freed instance id`() {
        val instanceId = UUID.randomUUID()
        val tool = UnequipSlotTool(
            StubEquipmentService(
                unequipResult = UnequipResult.Unequipped(sampleInstance(instanceId, slot = null)),
            ),
            activity,
        )

        val res = tool.invoke(UnequipSlotRequest("MAIN_HAND"), toolContext)

        assertEquals("unequipped", res.kind)
        assertEquals("MAIN_HAND", res.slot)
        assertEquals(instanceId.toString(), res.instanceId)
    }

    @Test
    fun `empty slot returns kind=empty`() {
        val tool = UnequipSlotTool(StubEquipmentService(unequipResult = UnequipResult.SlotEmpty), activity)

        val res = tool.invoke(UnequipSlotRequest("HELMET"), toolContext)

        assertEquals("empty", res.kind)
        assertEquals("HELMET", res.slot)
        assertEquals(null, res.instanceId)
    }

    @Test
    fun `unknown slot string is rejected with bad_request`() {
        val service = StubEquipmentService()
        val tool = UnequipSlotTool(service, activity)

        val res = tool.invoke(UnequipSlotRequest("WRONG"), toolContext)

        assertEquals("rejected", res.kind)
        assertEquals("bad_request", res.reason)
        assertEquals(0, service.unequipCalls.size)
    }

    private fun sampleInstance(id: UUID, slot: EquipSlot?) = EquipmentInstance(
        instanceId = id,
        agentId = agent,
        itemId = ItemId("RUSTY_SWORD"),
        rarity = Rarity.COMMON,
        durabilityCurrent = 50,
        durabilityMax = 50,
        creatorAgentId = null,
        createdAtTick = 1L,
        equippedInSlot = slot,
    )

    private class StubEquipmentService(
        private val equipResult: EquipResult = EquipResult.Rejected(dev.gvart.genesara.world.EquipRejection.INSTANCE_NOT_FOUND),
        private val unequipResult: UnequipResult = UnequipResult.SlotEmpty,
        private val equippedMap: Map<EquipSlot, EquipmentInstance> = emptyMap(),
    ) : EquipmentService {
        val unequipCalls = mutableListOf<Pair<AgentId, EquipSlot>>()
        override fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot) = equipResult
        override fun unequip(agentId: AgentId, slot: EquipSlot): UnequipResult {
            unequipCalls += agentId to slot
            return unequipResult
        }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = equippedMap
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
