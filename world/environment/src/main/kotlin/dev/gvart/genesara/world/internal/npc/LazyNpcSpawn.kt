package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeClearedTimestampStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
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
        val capacity = worldDef.biomes[biome]?.nodeNpcCapacity ?: 0
        if (capacity <= 0) return state to emptyList()

        val pool = catalog.byBiome(biome)
        if (pool.isEmpty()) return state to emptyList()

        val existing = state.npcs.values.count { it.nodeId == destination }
        if (existing > 0) return state to emptyList()

        val lastCleared = state.nodesClearedThisTick[destination]
            ?: clearedStore.lastClearedTick(destination)
        if (tick - lastCleared < balance.npcRespawnTicks()) return state to emptyList()

        val totalWeight = pool.sumOf { it.spawnWeight }
        if (totalWeight <= 0) return state to emptyList()
        val toSpawn = capacity
        val spawned = mutableListOf<Npc>()
        repeat(toSpawn) {
            val pick = weightedPick(pool, totalWeight, rng)
            val npc = Npc(
                id = NpcId(UUID.randomUUID()),
                type = pick.type,
                nodeId = destination,
                spawnNodeId = destination,
                hpCurrent = pick.hpMax,
                hpMax = pick.hpMax,
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
}

private fun weightedPick(pool: List<NpcDef>, totalWeight: Int, rng: Random): NpcDef {
    val pick = rng.nextInt(totalWeight.coerceAtLeast(1))
    var acc = 0
    for (entry in pool) {
        acc += entry.spawnWeight
        if (pick < acc) return entry
    }
    return pool.last()
}
