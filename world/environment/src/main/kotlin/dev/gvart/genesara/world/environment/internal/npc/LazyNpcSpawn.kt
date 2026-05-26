package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.NodeClearedTimestampStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneLookup
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import org.springframework.stereotype.Component

/**
 * Lazy-on-entry spawn: when an agent arrives at [destination], if the node has
 * been "cleared" (no living NPC) for at least `npcRespawnTicks`, fill the
 * biome's per-node capacity with NPCs picked by `spawn-weight` from the
 * catalog entries eligible for the destination's biome.
 *
 * No-op when:
 * - the destination biome's `nodeNpcCapacity` is 0,
 * - the destination is not yet rest-eligible,
 * - the node already has live NPCs,
 * - no catalog entries declare this biome.
 *
 * Returns the post-spawn [WorldState] (with the new [Npc] rows added to
 * [WorldState.npcs] / [WorldState.dirtyNpcs]) and an [EnvironmentEvent.NpcSpawned]
 * event per fresh spawn so observers in the active set learn the world
 * just got busier.
 */
/**
 * Movement-reducer hook contract. Production wires [LazyNpcSpawn]; tests can
 * pass a no-op so reducer fixtures don't need the catalog / balance / cleared-tick
 * graph.
 */
fun interface LazyNpcSpawnHook {
    fun maybeSeed(
        state: WorldState,
        destination: NodeId,
        agent: AgentId,
        tick: Long,
        rng: Random,
    ): Pair<WorldState, List<WorldEvent>>

    companion object {
        val NoOp = LazyNpcSpawnHook { state, _, _, _, _ -> state to emptyList() }
    }
}

@Component
class LazyNpcSpawn(
    private val catalog: NpcCatalog,
    private val balance: BalanceLookup,
    private val worldDef: WorldDefinitionProperties,
    private val clearedStore: NodeClearedTimestampStore,
    private val zoneLookup: NpcZoneLookup,
) : LazyNpcSpawnHook {
    /**
     * Hook from [dev.gvart.genesara.world.internal.movement.MovementReducer] —
     * see also `lazyNpcSpawnInstant` for unit-test entry that bypasses the
     * Spring bean wiring.
     */
    override fun maybeSeed(
        state: WorldState,
        destination: NodeId,
        agent: AgentId,
        tick: Long,
        rng: Random,
    ): Pair<WorldState, List<WorldEvent>> {
        val node = state.nodes[destination] ?: return state to emptyList()
        val region = state.regions[node.regionId] ?: return state to emptyList()
        val biome = region.biome ?: return state to emptyList()
        val biomeProps = worldDef.biomes[biome] ?: return state to emptyList()

        val zone = zoneLookup.resolveFor(destination, region.id)
        val capacity = zone?.maxConcurrent ?: biomeProps.nodeNpcCapacity
        if (capacity <= 0) return state to emptyList()

        if (zone == null && !nodeSelectedForSpawn(destination, biomeProps.nodeSpawnProbability)) {
            return state to emptyList()
        }

        val pool = resolvePool(zone, biome)
        if (pool.isEmpty()) return state to emptyList()

        val existing = state.npcs.values.count { it.nodeId == destination }
        if (existing > 0) return state to emptyList()

        val respawnThreshold = zone?.respawnTicks?.toLong() ?: balance.npcRespawnTicks()
        val lastCleared = state.nodesClearedThisTick[destination]
            ?: clearedStore.lastClearedTick(destination)
        if (tick - lastCleared < respawnThreshold) return state to emptyList()

        val totalWeight = pool.sumOf { it.weight }
        if (totalWeight <= 0) return state to emptyList()
        val spawned = mutableListOf<Npc>()
        repeat(capacity) {
            val pick = weightedPick(pool, totalWeight, rng)
            val npc = Npc(
                id = NpcId(UUID.randomUUID()),
                type = pick.def.type,
                nodeId = destination,
                spawnNodeId = destination,
                hpCurrent = pick.def.hpMax,
                hpMax = pick.def.hpMax,
                spawnedAtTick = tick,
                lastAttackTick = tick,
            )
            spawned += npc
        }

        var nextState = state
        val events = mutableListOf<WorldEvent>()
        for (npc in spawned) {
            nextState = nextState.addSpawnedNpc(npc)
            events += EnvironmentEvent.NpcSpawned(
                npc = npc.id,
                npcType = npc.type,
                at = npc.nodeId,
                hpMax = npc.hpMax,
                tick = tick,
                causedBy = null,
            )
        }
        return nextState to events
    }

    // Zone weights override the catalog's spawn-weight, but `spawnBiomes` still applies:
    // a zone can't conjure wolves into a desert biome that doesn't list them.
    private fun resolvePool(zone: NpcZone?, biome: Biome): List<WeightedDef> {
        if (zone == null) {
            return catalog.byBiome(biome).map { WeightedDef(it, it.spawnWeight) }
        }
        return zone.weights.mapNotNull { (type, weight) ->
            val def = catalog.byType(type) ?: return@mapNotNull null
            if (biome !in def.spawnBiomes) return@mapNotNull null
            WeightedDef(def, weight)
        }
    }
}

internal fun nodeSelectedForSpawn(nodeId: NodeId, probability: Double): Boolean {
    if (probability >= 1.0) return true
    if (probability <= 0.0) return false
    return nodeSpawnFraction(nodeId) < probability
}

// SplitMix64 finalizer — keeps adjacent nodeIds from clustering into the same spawn/no-spawn bucket.
private fun nodeSpawnFraction(nodeId: NodeId): Double {
    var z = nodeId.value xor 0x9E3779B97F4A7C15UL.toLong()
    z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B7UL.toLong()
    z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
    z = z xor (z ushr 31)
    val unsigned = z ushr 11
    return unsigned.toDouble() / (1L shl 53).toDouble()
}

internal data class WeightedDef(val def: NpcDef, val weight: Int)

private fun weightedPick(pool: List<WeightedDef>, totalWeight: Int, rng: Random): WeightedDef {
    val pick = rng.nextInt(totalWeight.coerceAtLeast(1))
    var acc = 0
    for (entry in pool) {
        acc += entry.weight
        if (pick < acc) return entry
    }
    return pool.last()
}
