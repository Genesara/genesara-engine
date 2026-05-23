package dev.gvart.genesara.world.environment.internal.instances

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
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
import org.jooq.exception.IntegrityConstraintViolationException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@Testcontainers
class JooqAgentItemInstancesStoreMountGearIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("mount_gear_it")
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
    private val mount = MountId(UUID.randomUUID())
    private val otherMount = MountId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_ITEM_INSTANCES).cascade().execute()
        store = JooqAgentItemInstancesStore(dsl)
    }

    @Test
    fun `assignToMountSlot updates the row and returns the equipped instance`() {
        val gear = mountGear(agent, tick = 1L)
        store.insert(gear)

        val updated = assertNotNull(store.assignToMountSlot(gear.instanceId, agent, mount, MountSlot.SADDLE))

        assertEquals(mount.value, updated.equippedOnMount)
        assertEquals(MountSlot.SADDLE, updated.equippedMountSlot)
    }

    @Test
    fun `assignToMountSlot is rejected when the agent does not match`() {
        val gear = mountGear(agent, tick = 1L)
        store.insert(gear)

        assertNull(store.assignToMountSlot(gear.instanceId, AgentId(UUID.randomUUID()), mount, MountSlot.SADDLE))
    }

    @Test
    fun `assignToMountSlot is rejected for non-MOUNT_GEAR rows`() {
        val equipment = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = ItemId("IRON_SWORD"),
            rarity = Rarity.COMMON,
            durabilityCurrent = 50,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
        )
        store.insert(equipment)

        assertNull(store.assignToMountSlot(equipment.instanceId, agent, mount, MountSlot.SADDLE))
    }

    @Test
    fun `assignToMountSlot raises a unique-violation on a slot collision`() {
        val first = mountGear(agent, tick = 1L)
        val second = mountGear(agent, tick = 2L)
        store.insert(first)
        store.insert(second)
        store.assignToMountSlot(first.instanceId, agent, mount, MountSlot.SADDLE)

        try {
            store.assignToMountSlot(second.instanceId, agent, mount, MountSlot.SADDLE)
            fail("expected unique-violation for second equip to the same slot")
        } catch (ex: IntegrityConstraintViolationException) {
            val message = generateSequence<Throwable>(ex) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
            assertTrue(
                "uq_agent_item_instances_equipped_mount_slot" in message ||
                    "23505" in message,
                "expected unique-index violation, got: $message",
            )
        }
    }

    @Test
    fun `clearMountSlot empties the row and returns the cleared instance`() {
        val gear = mountGear(agent, tick = 1L)
        store.insert(gear)
        store.assignToMountSlot(gear.instanceId, agent, mount, MountSlot.SADDLE)

        val cleared = assertNotNull(store.clearMountSlot(mount, MountSlot.SADDLE))

        assertNull(cleared.equippedOnMount)
        assertNull(cleared.equippedMountSlot)
        val refetched = assertNotNull(store.findById(gear.instanceId) as? ItemInstance.MountGear)
        assertNull(refetched.equippedOnMount)
    }

    @Test
    fun `clearMountSlot returns null when the slot was already empty`() {
        assertNull(store.clearMountSlot(mount, MountSlot.SADDLE))
    }

    @Test
    fun `byEquippedOnMount returns only rows bound to the queried mount`() {
        val a = mountGear(agent, tick = 1L)
        val b = mountGear(agent, tick = 2L)
        val c = mountGear(agent, tick = 3L)
        store.insert(a)
        store.insert(b)
        store.insert(c)
        store.assignToMountSlot(a.instanceId, agent, mount, MountSlot.SADDLE)
        store.assignToMountSlot(b.instanceId, agent, mount, MountSlot.BARDING)
        store.assignToMountSlot(c.instanceId, agent, otherMount, MountSlot.SADDLE)

        val onMount = store.byEquippedOnMount(mount)

        assertEquals(2, onMount.size)
        assertEquals(setOf(MountSlot.SADDLE, MountSlot.BARDING), onMount.mapNotNull { it.equippedMountSlot }.toSet())
        assertTrue(onMount.all { it.equippedOnMount == mount.value })
    }

    private fun mountGear(agent: AgentId, tick: Long) = ItemInstance.MountGear(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = ItemId("LEATHER_SADDLE"),
        rarity = Rarity.COMMON,
        durabilityCurrent = 50,
        durabilityMax = 100,
        creatorAgentId = null,
        createdAtTick = tick,
    )
}
