package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
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

class GetEquipmentToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `partitions equipped vs stash and projects each instance`() {
        val sword = sampleInstance(ItemId("RUSTY_SWORD"), slot = EquipSlot.MAIN_HAND)
        val helmet = sampleInstance(ItemId("LEATHER_HELMET"), slot = EquipSlot.HELMET)
        val spareDagger = sampleInstance(ItemId("DAGGER"), slot = null)
        val spareRing = sampleInstance(ItemId("BRASS_RING"), slot = null)

        val store = StubStore(listOf(sword, helmet, spareDagger, spareRing))
        val tool = GetEquipmentTool(store, activity)

        val res = tool.invoke(GetEquipmentRequest(), toolContext)

        assertEquals(setOf("MAIN_HAND", "HELMET"), res.equipped.keys)
        assertEquals("RUSTY_SWORD", res.equipped["MAIN_HAND"]?.itemId)
        assertEquals("LEATHER_HELMET", res.equipped["HELMET"]?.itemId)
        assertEquals(2, res.stash.size)
        assertEquals(setOf("DAGGER", "BRASS_RING"), res.stash.map { it.itemId }.toSet())
        // Stash entries should not carry an `equippedInSlot` value.
        assertEquals(setOf<String?>(null), res.stash.map { it.equippedInSlot }.toSet())
    }

    @Test
    fun `empty agent returns empty equipped + empty stash`() {
        val tool = GetEquipmentTool(StubStore(emptyList()), activity)

        val res = tool.invoke(GetEquipmentRequest(), toolContext)

        assertEquals(emptyMap(), res.equipped)
        assertEquals(emptyList(), res.stash)
    }

    private fun sampleInstance(itemId: ItemId, slot: EquipSlot?) = EquipmentInstance(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = itemId,
        rarity = Rarity.COMMON,
        durabilityCurrent = 50,
        durabilityMax = 50,
        creatorAgentId = null,
        createdAtTick = 1L,
        equippedInSlot = slot,
    )

    private class StubStore(private val instances: List<EquipmentInstance>) : EquipmentInstanceStore {
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? =
            instances.firstOrNull { it.instanceId == instanceId }
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
            instances.filter { it.agentId == agentId }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            instances.filter { it.equippedInSlot != null }.associateBy { it.equippedInSlot!! }
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
