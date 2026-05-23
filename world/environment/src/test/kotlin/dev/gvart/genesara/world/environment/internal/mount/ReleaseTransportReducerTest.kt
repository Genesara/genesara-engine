package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ReleaseTransportReducerTest {

    private val owner = AgentId(UUID.randomUUID())
    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)

    @Test
    fun `owner releases mount at same node — emits TransportReleased and clears owner`() {
        val mount = mount(owner = owner, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(owner to nodeA))

        val result = reduceReleaseTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ReleaseTransport(agent = owner, mount = mount.id),
            mounts = store,
            tick = 7L,
        ).getOrNull()

        val output = assertNotNull(result)
        val event = output.events.filterIsInstance<EnvironmentEvent.TransportReleased>().single()
        assertEquals(mount.id, event.mount)
        assertEquals(mount.type, event.mountType)
        assertEquals(nodeA, event.at)
        assertEquals(7L, event.tick)
        assertNull(store.findById(mount.id)!!.ownerAgentId)
    }

    @Test
    fun `not in world is rejected with NotInWorld`() {
        val mount = mount(owner = owner, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = emptyMap())

        val rejection = reduceReleaseTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ReleaseTransport(agent = owner, mount = mount.id),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        assertIs<WorldRejection.NotInWorld>(rejection)
    }

    @Test
    fun `unknown mount id is rejected with UnknownMount`() {
        val store = InMemoryMountStore()
        val state = stateWith(positions = mapOf(owner to nodeA))
        val unknown = MountId(UUID.randomUUID())

        val rejection = reduceReleaseTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ReleaseTransport(agent = owner, mount = unknown),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.UnknownMount>(rejection)
        assertEquals(unknown, r.mount)
    }

    @Test
    fun `non-owner is rejected with NotYourMount`() {
        val otherOwner = AgentId(UUID.randomUUID())
        val mount = mount(owner = otherOwner, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(owner to nodeA))

        val rejection = reduceReleaseTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ReleaseTransport(agent = owner, mount = mount.id),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.NotYourMount>(rejection)
        assertEquals(mount.id, r.mount)
        assertEquals(otherOwner, store.findById(mount.id)!!.ownerAgentId)
    }

    @Test
    fun `ownerless mount is rejected with NotYourMount`() {
        val mount = mount(owner = null, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(owner to nodeA))

        val rejection = reduceReleaseTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ReleaseTransport(agent = owner, mount = mount.id),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        assertIs<WorldRejection.NotYourMount>(rejection)
    }

    @Test
    fun `agent at different node is rejected with MountNotAtSameNode`() {
        val mount = mount(owner = owner, at = nodeB)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(owner to nodeA))

        val rejection = reduceReleaseTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ReleaseTransport(agent = owner, mount = mount.id),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.MountNotAtSameNode>(rejection)
        assertEquals(nodeA, r.agentAt)
        assertEquals(nodeB, r.mountAt)
        assertEquals(owner, store.findById(mount.id)!!.ownerAgentId)
    }

    private fun stateWith(positions: Map<AgentId, NodeId>): WorldState =
        WorldState(positions = positions)

    private fun mount(owner: AgentId?, at: NodeId): Mount = Mount(
        id = MountId(UUID.randomUUID()),
        type = MountType("RIDING_HORSE"),
        ownerAgentId = owner,
        nodeId = at,
        hpCurrent = 50,
        hpMax = 50,
        hunger = 100,
        hungerMax = 100,
        fatigue = 100,
        fatigueMax = 100,
        mountedByAgentId = null,
        tamedAtTick = 0L,
    )
}

internal class InMemoryMountStore : MountInstanceStore {
    private val byId = mutableMapOf<MountId, Mount>()
    override fun insert(mount: Mount) { byId[mount.id] = mount }
    override fun findById(mountId: MountId): Mount? = byId[mountId]
    override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> =
        byId.values.filter { it.nodeId in nodeIds }
    override fun byOwner(agentId: AgentId): List<Mount> =
        byId.values.filter { it.ownerAgentId == agentId }
    override fun findByRider(agentId: AgentId): Mount? =
        byId.values.firstOrNull { it.mountedByAgentId == agentId }
    override fun all(): List<Mount> = byId.values.toList()
    override fun delete(mountId: MountId): Boolean = byId.remove(mountId) != null
    override fun update(mount: Mount): Boolean =
        if (byId.containsKey(mount.id)) { byId[mount.id] = mount; true } else false
}

internal fun stubBalance(): BalanceLookup = object : BalanceLookup {
    override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
    override fun staminaRegenPerTick(climate: Climate) = 0
    override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
    override fun harvestStaminaCost(item: ItemId): Int = 5
    override fun harvestYield(item: ItemId): Int = 1
    override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
    override fun gaugeLowThreshold(gauge: Gauge): Int = 25
    override fun starvationDamagePerTick(): Int = 0
    override fun isWaterSource(terrain: Terrain): Boolean = false
    override fun drinkStaminaCost(): Int = 1
    override fun drinkThirstRefill(): Int = 25
    override fun sleepRegenPerOfflineTick(): Int = 0
    override fun isTraversable(terrain: Terrain): Boolean = true
    override fun xpLossOnDeath(): Int = 0
    override fun killStreakWindowTicks(): Long = 1000L
    override fun dropChanceForKillCount(killCount: Int): Double = 0.0
}

