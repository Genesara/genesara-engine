package dev.gvart.genesara.world.environment.internal.instances

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.environment.internal.mount.JooqMountInstanceStore
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_ITEM_INSTANCES
import dev.gvart.genesara.world.internal.jooq.tables.references.MOUNTS
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Testcontainers
class JooqAgentItemInstancesStoreReassignOwnerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("item_instances_reassign_it")
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
    private lateinit var mounts: JooqMountInstanceStore
    private val from = AgentId(UUID.randomUUID())
    private val to = AgentId(UUID.randomUUID())
    private val mountId = MountId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_ITEM_INSTANCES).cascade().execute()
        dsl.truncate(MOUNTS).cascade().execute()
        store = JooqAgentItemInstancesStore(dsl)
        mounts = JooqMountInstanceStore(dsl)
        mounts.insert(sampleMount(mountId))
    }

    @Test
    fun `reassignOwner flips agent_id on an unbound instance`() {
        val instance = equipment(from)
        store.insert(instance)

        val updated = assertNotNull(store.reassignOwner(instance.instanceId, from, to))

        assertEquals(to, updated.agentId)
        assertEquals(to.id, currentAgentId(instance.instanceId))
    }

    @Test
    fun `reassignOwner rejects when the instance does not exist`() {
        val phantom = UUID.randomUUID()
        assertNull(store.reassignOwner(phantom, from, to))
    }

    @Test
    fun `reassignOwner rejects when the from-agent does not match the row owner`() {
        val instance = equipment(from)
        store.insert(instance)

        assertNull(store.reassignOwner(instance.instanceId, to, from))
        assertEquals(from.id, currentAgentId(instance.instanceId))
    }

    @Test
    fun `reassignOwner rejects when the instance is currently equipped in a slot`() {
        val instance = equipment(from, slot = EquipSlot.MAIN_HAND)
        store.insert(instance)

        assertNull(store.reassignOwner(instance.instanceId, from, to))
        assertEquals(from.id, currentAgentId(instance.instanceId))
    }

    @Test
    fun `reassignOwner rejects when the instance is equipped on a mount`() {
        val gear = mountGear(from, equippedOnMount = mountId, slot = MountSlot.SADDLE)
        store.insert(gear)

        assertNull(store.reassignOwner(gear.instanceId, from, to))
        assertEquals(from.id, currentAgentId(gear.instanceId))
    }

    @Test
    fun `reassignOwner rejects when the instance is stowed on a mount`() {
        val instance = equipment(from)
        store.insert(instance)
        store.stowOnMount(instance.instanceId, from, mountId)

        assertNull(store.reassignOwner(instance.instanceId, from, to))
        assertEquals(from.id, currentAgentId(instance.instanceId))
    }

    private fun currentAgentId(instanceId: UUID): UUID? =
        dsl.select(AGENT_ITEM_INSTANCES.AGENT_ID)
            .from(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .fetchOne(AGENT_ITEM_INSTANCES.AGENT_ID)

    private fun equipment(agent: AgentId, slot: EquipSlot? = null): ItemInstance.Equipment =
        ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = ItemId("IRON_SWORD"),
            rarity = Rarity.COMMON,
            durabilityCurrent = 50,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
            equippedInSlot = slot,
        )

    private fun mountGear(
        agent: AgentId,
        equippedOnMount: MountId? = null,
        slot: MountSlot? = null,
    ): ItemInstance.MountGear = ItemInstance.MountGear(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = ItemId("LEATHER_SADDLE"),
        rarity = Rarity.COMMON,
        durabilityCurrent = 50,
        durabilityMax = 100,
        creatorAgentId = null,
        createdAtTick = 1L,
        equippedOnMount = equippedOnMount?.value,
        equippedMountSlot = slot,
    )

    private fun sampleMount(id: MountId): Mount = Mount(
        id = id,
        type = MountType("RIDING_HORSE"),
        nodeId = NodeId(1L),
        hpCurrent = 80,
        hpMax = 80,
        hunger = 100,
        hungerMax = 100,
        fatigue = 100,
        fatigueMax = 100,
        mountedByAgentId = null,
        tamedAtTick = 100L,
    )
}
