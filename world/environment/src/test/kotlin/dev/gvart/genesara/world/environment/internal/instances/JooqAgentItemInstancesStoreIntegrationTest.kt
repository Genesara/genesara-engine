package dev.gvart.genesara.world.environment.internal.instances

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_ITEM_INSTANCES
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentItemInstancesStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("item_instances_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private lateinit var store: JooqAgentItemInstancesStore
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_ITEM_INSTANCES).cascade().execute()
        store = JooqAgentItemInstancesStore(dsl)
    }

    @Test
    fun `roundtrip Equipment and Key through the same store in one transaction`() {
        val sword = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = ItemId("IRON_SWORD"),
            rarity = Rarity.UNCOMMON,
            durabilityCurrent = 75,
            durabilityMax = 100,
            creatorAgentId = otherAgent,
            createdAtTick = 1L,
            equippedInSlot = EquipSlot.MAIN_HAND,
        )
        val gateId = UUID.randomUUID()
        val key = ItemInstance.Key(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = ItemId("GATE_KEY"),
            gateInstanceId = gateId,
            createdAtTick = 2L,
        )

        store.insert(sword)
        store.insert(key)

        val readSword = assertNotNull(store.findById(sword.instanceId))
        assertTrue(readSword is ItemInstance.Equipment, "category=EQUIPMENT must materialise as Equipment")
        assertEquals(sword.rarity, readSword.rarity)
        assertEquals(sword.durabilityCurrent, readSword.durabilityCurrent)
        assertEquals(sword.creatorAgentId, readSword.creatorAgentId)
        assertEquals(EquipSlot.MAIN_HAND, readSword.equippedInSlot)

        val readKey = assertNotNull(store.findById(key.instanceId))
        assertTrue(readKey is ItemInstance.Key, "category=KEY must materialise as Key")
        assertEquals(gateId, readKey.gateInstanceId)
    }

    @Test
    fun `listByAgent returns both categories interleaved by createdAtTick`() {
        store.insert(equipmentOf(agent, tick = 10L))
        store.insert(keyOf(agent, gate = UUID.randomUUID(), tick = 5L))
        store.insert(equipmentOf(agent, tick = 15L))

        val all = store.listByAgent(agent)
        assertEquals(3, all.size)
        assertEquals(listOf(5L, 10L, 15L), all.map { it.createdAtTick })
        assertTrue(all[0] is ItemInstance.Key)
        assertTrue(all[1] is ItemInstance.Equipment)
        assertTrue(all[2] is ItemInstance.Equipment)
    }

    @Test
    fun `agentHoldsKeyFor only matches KEY rows`() {
        val gateId = UUID.randomUUID()
        store.insert(keyOf(agent, gate = gateId, tick = 1L))
        // An EQUIPMENT row with NO bound_building_id — must not register as a key match.
        store.insert(equipmentOf(agent, tick = 2L))

        assertTrue(store.agentHoldsKeyFor(agent, gateId))
        assertFalse(store.agentHoldsKeyFor(otherAgent, gateId), "other agents' keys must not match")
        assertFalse(store.agentHoldsKeyFor(agent, UUID.randomUUID()), "different gate id must not match")
    }

    @Test
    fun `equippedFor narrows to Equipment subtype and ignores KEY rows`() {
        store.insert(
            equipmentOf(agent, slot = EquipSlot.HELMET, tick = 1L),
        )
        // A key held by the same agent must not show up under equipment slots.
        store.insert(keyOf(agent, gate = UUID.randomUUID(), tick = 2L))

        val equipped = store.equippedFor(agent)
        assertEquals(1, equipped.size)
        assertEquals(EquipSlot.HELMET, equipped.keys.single())
    }

    @Test
    fun `assignToSlot is rejected for KEY rows even when the (agent, instance) matches`() {
        val key = keyOf(agent, gate = UUID.randomUUID(), tick = 1L)
        store.insert(key)
        assertNull(
            store.assignToSlot(key.instanceId, agent, EquipSlot.MAIN_HAND),
            "assignToSlot must filter to EQUIPMENT — keys are not slottable",
        )
    }

    @Test
    fun `decrementDurability floors at zero and only touches EQUIPMENT`() {
        val sword = equipmentOf(agent, durabilityCurrent = 3, durabilityMax = 10, tick = 1L)
        store.insert(sword)
        val key = keyOf(agent, gate = UUID.randomUUID(), tick = 2L)
        store.insert(key)

        val damaged = assertNotNull(store.decrementDurability(sword.instanceId, amount = 5))
        assertEquals(0, damaged.durabilityCurrent)
        assertNull(
            store.decrementDurability(key.instanceId, amount = 1),
            "decrementDurability must be a no-op for KEY rows",
        )
    }

    @Test
    fun `delete removes by id regardless of category`() {
        val sword = equipmentOf(agent, tick = 1L)
        val key = keyOf(agent, gate = UUID.randomUUID(), tick = 2L)
        store.insert(sword)
        store.insert(key)

        assertTrue(store.delete(sword.instanceId))
        assertTrue(store.delete(key.instanceId))
        assertEquals(0, store.listByAgent(agent).size)
    }

    private fun equipmentOf(
        agent: AgentId,
        slot: EquipSlot? = null,
        durabilityCurrent: Int = 50,
        durabilityMax: Int = 100,
        tick: Long,
    ) = ItemInstance.Equipment(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = ItemId("IRON_SWORD"),
        rarity = Rarity.COMMON,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        creatorAgentId = null,
        createdAtTick = tick,
        equippedInSlot = slot,
    )

    private fun keyOf(agent: AgentId, gate: UUID, tick: Long) = ItemInstance.Key(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = ItemId("GATE_KEY"),
        gateInstanceId = gate,
        createdAtTick = tick,
    )
}
