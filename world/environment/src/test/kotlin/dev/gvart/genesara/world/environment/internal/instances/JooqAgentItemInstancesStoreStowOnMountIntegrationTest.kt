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
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentItemInstancesStoreStowOnMountIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("item_instances_stow_it")
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
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val mountId = MountId(UUID.randomUUID())
    private val otherMountId = MountId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_ITEM_INSTANCES).cascade().execute()
        dsl.truncate(MOUNTS).cascade().execute()
        store = JooqAgentItemInstancesStore(dsl)
        mounts = JooqMountInstanceStore(dsl)
        mounts.insert(sampleMount(mountId))
        mounts.insert(sampleMount(otherMountId))
    }

    @Test
    fun `stowOnMount sets stowed_in_mount_id on an unequipped instance`() {
        val instance = equipment(agent)
        store.insert(instance)

        val stowed = assertNotNull(store.stowOnMount(instance.instanceId, agent, mountId))

        assertEquals(instance.instanceId, stowed.instanceId)
        assertEquals(mountId.value, currentStowedMountId(instance.instanceId))
    }

    @Test
    fun `stowOnMount rejects when the agent does not own the instance`() {
        val instance = equipment(agent)
        store.insert(instance)

        assertNull(store.stowOnMount(instance.instanceId, otherAgent, mountId))
        assertNull(currentStowedMountId(instance.instanceId))
    }

    @Test
    fun `stowOnMount rejects when the instance is equipped in an agent slot`() {
        val instance = equipment(agent, slot = EquipSlot.MAIN_HAND)
        store.insert(instance)

        assertNull(store.stowOnMount(instance.instanceId, agent, mountId))
        assertNull(currentStowedMountId(instance.instanceId))
    }

    @Test
    fun `stowOnMount rejects when the instance is equipped on a mount`() {
        val gear = mountGear(agent, equippedOnMount = mountId, slot = MountSlot.SADDLE)
        store.insert(gear)

        assertNull(store.stowOnMount(gear.instanceId, agent, otherMountId))
    }

    @Test
    fun `unstowFromMount clears the column and returns the row`() {
        val instance = equipment(agent)
        store.insert(instance)
        store.stowOnMount(instance.instanceId, agent, mountId)

        val cleared = assertNotNull(store.unstowFromMount(instance.instanceId))

        assertEquals(instance.instanceId, cleared.instanceId)
        assertNull(currentStowedMountId(instance.instanceId))
    }

    @Test
    fun `unstowFromMount on an already-clear row is a no-op returning the row`() {
        val instance = equipment(agent)
        store.insert(instance)

        val result = assertNotNull(store.unstowFromMount(instance.instanceId))

        assertEquals(instance.instanceId, result.instanceId)
        assertNull(currentStowedMountId(instance.instanceId))
    }

    @Test
    fun `byStowedOnMount lists every instance currently riding in cargo`() {
        val first = equipment(agent)
        val second = equipment(agent)
        val onOther = equipment(agent)
        store.insert(first); store.insert(second); store.insert(onOther)
        store.stowOnMount(first.instanceId, agent, mountId)
        store.stowOnMount(second.instanceId, agent, mountId)
        store.stowOnMount(onOther.instanceId, agent, otherMountId)

        val rows = store.byStowedOnMount(mountId)

        assertEquals(setOf(first.instanceId, second.instanceId), rows.map { it.instanceId }.toSet())
        assertTrue(rows.all { it is ItemInstance.Equipment })
    }

    private fun currentStowedMountId(instanceId: UUID): UUID? =
        dsl.select(AGENT_ITEM_INSTANCES.STOWED_IN_MOUNT_ID)
            .from(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .fetchOne(AGENT_ITEM_INSTANCES.STOWED_IN_MOUNT_ID)

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
