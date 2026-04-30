package dev.gvart.genesara.world.internal.equipment

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_EQUIPMENT_INSTANCES
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [JooqEquipmentInstanceStore] — the per-instance equipment
 * substrate that future slices (equipment slots, crafting, drops) will populate.
 * Verifies insert / list / GREATEST-clamped durability decrement / delete semantics
 * against a real Postgres so the SQL clamp doesn't silently round-trip negative values.
 */
@Testcontainers
class JooqEquipmentInstanceStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("equipment_it")
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

    private lateinit var store: JooqEquipmentInstanceStore
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val creator = AgentId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_EQUIPMENT_INSTANCES).cascade().execute()
        store = JooqEquipmentInstanceStore(dsl)
    }

    @Test
    fun `listByAgent is empty for an agent with no equipment`() {
        assertEquals(emptyList(), store.listByAgent(agent))
    }

    @Test
    fun `insert and listByAgent round-trip a full instance with creator`() {
        val instance = sampleInstance(agentId = agent, creator = creator, rarity = Rarity.RARE)
        store.insert(instance)

        val recalled = store.listByAgent(agent).single()
        assertEquals(instance, recalled)
    }

    @Test
    fun `insert and listByAgent round-trip a creator-less instance (loot drop)`() {
        val instance = sampleInstance(agentId = agent, creator = null, rarity = Rarity.UNCOMMON)
        store.insert(instance)

        val recalled = store.listByAgent(agent).single()
        assertEquals(null, recalled.creatorAgentId)
        assertEquals(Rarity.UNCOMMON, recalled.rarity)
    }

    @Test
    fun `listByAgent is per-agent — one agent does not see another's gear`() {
        val mine = sampleInstance(agentId = agent, creator = creator, rarity = Rarity.COMMON)
        val theirs = sampleInstance(agentId = otherAgent, creator = null, rarity = Rarity.EPIC)
        store.insert(mine)
        store.insert(theirs)

        assertEquals(listOf(mine), store.listByAgent(agent))
        assertEquals(listOf(theirs), store.listByAgent(otherAgent))
    }

    @Test
    fun `listByAgent ordering is stable on instance_id`() {
        val ids = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
        ids.forEach { store.insert(sampleInstance(agentId = agent, instanceId = it)) }

        assertEquals(ids.sorted(), store.listByAgent(agent).map { it.instanceId })
    }

    @Test
    fun `decrementDurability subtracts and returns the updated row`() {
        val instance = sampleInstance(
            agentId = agent,
            durabilityCurrent = 50,
            durabilityMax = 100,
        )
        store.insert(instance)

        val updated = assertNotNull(store.decrementDurability(instance.instanceId, amount = 10))
        assertEquals(40, updated.durabilityCurrent)
        assertEquals(100, updated.durabilityMax)
        assertFalse(updated.isBroken)
    }

    @Test
    fun `decrementDurability clamps at zero rather than going negative`() {
        // Critical SQL-level invariant: the GREATEST(...) clamp must execute server-
        // side. If the wire format ever loses the clamp the CHECK constraint would
        // reject the update and the row state would be inconsistent.
        val instance = sampleInstance(agentId = agent, durabilityCurrent = 5, durabilityMax = 100)
        store.insert(instance)

        val updated = assertNotNull(store.decrementDurability(instance.instanceId, amount = 999))
        assertEquals(0, updated.durabilityCurrent)
        assertTrue(updated.isBroken)
    }

    @Test
    fun `decrementDurability returns null for a missing instance`() {
        val phantom = UUID.randomUUID()
        assertNull(store.decrementDurability(phantom, amount = 1))
    }

    @Test
    fun `decrementDurability with amount zero is a no-op that still returns the current row`() {
        val instance = sampleInstance(agentId = agent, durabilityCurrent = 50, durabilityMax = 100)
        store.insert(instance)

        val updated = assertNotNull(store.decrementDurability(instance.instanceId, amount = 0))
        assertEquals(50, updated.durabilityCurrent)
    }

    @Test
    fun `decrementDurability on an already-broken row stays at zero and returns the row`() {
        // Pin the contract: re-decrementing a broken instance is idempotent on the
        // value (clamp at zero) and returns the row, NOT null. This lets the next
        // slice's wear-and-tear flow distinguish "row missing" (decrement returns
        // null, e.g. another agent already deleted it) from "row already broken"
        // (decrement returns row with isBroken == true) without an extra read.
        val instance = sampleInstance(agentId = agent, durabilityCurrent = 0, durabilityMax = 100)
        store.insert(instance)

        val updated = assertNotNull(store.decrementDurability(instance.instanceId, amount = 5))
        assertEquals(0, updated.durabilityCurrent)
        assertTrue(updated.isBroken)
    }

    @Test
    fun `delete removes the instance and listByAgent reflects the absence`() {
        val instance = sampleInstance(agentId = agent)
        store.insert(instance)

        assertTrue(store.delete(instance.instanceId))
        assertEquals(emptyList(), store.listByAgent(agent))
    }

    @Test
    fun `delete returns false for a missing instance`() {
        assertFalse(store.delete(UUID.randomUUID()))
    }

    @Test
    fun `insert with duplicate instance_id throws DataAccessException on PK conflict`() {
        val instance = sampleInstance(agentId = agent)
        store.insert(instance)
        // Re-insert with the same id hits the PK uniqueness violation. Pinning
        // the exception class so future retry-logic callers know what to catch.
        assertFailsWith<DataAccessException> { store.insert(instance) }
    }

    // ─────────────────────── Slot operations (Slice C1) ───────────────────────

    @Test
    fun `findById returns the instance and round-trips equippedInSlot`() {
        val unequipped = sampleInstance(agentId = agent)
        val equipped = sampleInstance(agentId = agent, slot = EquipSlot.MAIN_HAND)
        store.insert(unequipped)
        store.insert(equipped)

        assertEquals(null, store.findById(unequipped.instanceId)?.equippedInSlot)
        assertEquals(EquipSlot.MAIN_HAND, store.findById(equipped.instanceId)?.equippedInSlot)
    }

    @Test
    fun `findById returns null for a missing instance`() {
        assertEquals(null, store.findById(UUID.randomUUID()))
    }

    @Test
    fun `equippedFor returns only currently-equipped instances keyed by slot`() {
        val held = sampleInstance(agentId = agent)
        val mainHand = sampleInstance(agentId = agent, slot = EquipSlot.MAIN_HAND)
        val helmet = sampleInstance(agentId = agent, slot = EquipSlot.HELMET)
        store.insert(held)
        store.insert(mainHand)
        store.insert(helmet)

        val map = store.equippedFor(agent)

        assertEquals(setOf(EquipSlot.MAIN_HAND, EquipSlot.HELMET), map.keys)
        assertEquals(mainHand.instanceId, map[EquipSlot.MAIN_HAND]?.instanceId)
        assertEquals(helmet.instanceId, map[EquipSlot.HELMET]?.instanceId)
    }

    @Test
    fun `assignToSlot moves an instance and clearSlot frees it`() {
        val instance = sampleInstance(agentId = agent)
        store.insert(instance)

        val equipped = assertNotNull(store.assignToSlot(instance.instanceId, agent, EquipSlot.MAIN_HAND))
        assertEquals(EquipSlot.MAIN_HAND, equipped.equippedInSlot)
        assertEquals(EquipSlot.MAIN_HAND, store.equippedFor(agent)[EquipSlot.MAIN_HAND]?.equippedInSlot)

        val cleared = assertNotNull(store.clearSlot(agent, EquipSlot.MAIN_HAND))
        assertEquals(instance.instanceId, cleared.instanceId)
        assertEquals(null, cleared.equippedInSlot)
        assertEquals(emptyMap(), store.equippedFor(agent))
    }

    @Test
    fun `assignToSlot returns null for a missing instance`() {
        assertEquals(null, store.assignToSlot(UUID.randomUUID(), agent, EquipSlot.MAIN_HAND))
    }

    @Test
    fun `assignToSlot returns null when the instance belongs to a different agent`() {
        // Defense-in-depth: even if a buggy upstream caller forgets the
        // ownership check, the WHERE-clause predicate prevents one agent from
        // moving another agent's instance into their own slot grid.
        val otherAgent = AgentId(UUID.randomUUID())
        val instance = sampleInstance(agentId = otherAgent)
        store.insert(instance)

        assertEquals(null, store.assignToSlot(instance.instanceId, agent, EquipSlot.MAIN_HAND))
    }

    @Test
    fun `clearSlot returns null when the slot is already empty`() {
        assertEquals(null, store.clearSlot(agent, EquipSlot.MAIN_HAND))
    }

    @Test
    fun `unique partial index prevents two instances in the same (agent, slot)`() {
        // The SQL-level race fence: even if the equip service raced past its
        // pre-check, the second concurrent assignment would hit this index. We
        // simulate the race by trying to assign two different instances to the
        // same slot in sequence — the second call must fail with a
        // DataAccessException, not silently overwrite.
        val a = sampleInstance(agentId = agent)
        val b = sampleInstance(agentId = agent)
        store.insert(a)
        store.insert(b)
        store.assignToSlot(a.instanceId, agent, EquipSlot.MAIN_HAND)

        assertFailsWith<DataAccessException> {
            store.assignToSlot(b.instanceId, agent, EquipSlot.MAIN_HAND)
        }
    }

    @Test
    fun `partial index allows multiple unequipped (NULL) instances per agent`() {
        // The unique index is `WHERE equipped_in_slot IS NOT NULL` — without
        // the `WHERE`, every NULL pair would collide. Pin the contract so a
        // future migration "tightens" the index and breaks unequipped stashing.
        val a = sampleInstance(agentId = agent)
        val b = sampleInstance(agentId = agent)
        store.insert(a)
        store.insert(b)

        assertEquals(2, store.listByAgent(agent).size)
    }

    @Test
    fun `slot CHECK constraint rejects an unknown slot string at the DB level`() {
        // Bypass the Kotlin enum via raw SQL to confirm the schema-level fence.
        // If the CHECK constraint were missing, this would silently succeed and
        // poison `EquipSlot.valueOf` on the next read.
        val instance = sampleInstance(agentId = agent)
        store.insert(instance)

        assertFailsWith<DataAccessException> {
            dsl.update(dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_EQUIPMENT_INSTANCES)
                .set(
                    dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_EQUIPMENT_INSTANCES.EQUIPPED_IN_SLOT,
                    "GIBBERISH",
                )
                .where(
                    dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID
                        .eq(instance.instanceId),
                )
                .execute()
        }
    }

    private fun sampleInstance(
        agentId: AgentId,
        instanceId: UUID = UUID.randomUUID(),
        itemId: ItemId = ItemId("IRON_SWORD"),
        rarity: Rarity = Rarity.COMMON,
        durabilityCurrent: Int = 100,
        durabilityMax: Int = 100,
        creator: AgentId? = null,
        createdAtTick: Long = 1L,
        slot: EquipSlot? = null,
    ) = EquipmentInstance(
        instanceId = instanceId,
        agentId = agentId,
        itemId = itemId,
        rarity = rarity,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        creatorAgentId = creator,
        createdAtTick = createdAtTick,
        equippedInSlot = slot,
    )
}
