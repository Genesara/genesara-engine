package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.balance.BalanceLookup

internal class InMemoryMountStore : MountInstanceStore {
    private val byId = mutableMapOf<MountId, Mount>()
    override fun insert(mount: Mount) { byId[mount.id] = mount }
    override fun findById(mountId: MountId): Mount? = byId[mountId]
    override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> =
        byId.values.filter { it.nodeId in nodeIds }
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
}
