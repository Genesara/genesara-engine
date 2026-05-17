package dev.gvart.genesara.world.body.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.slf4j.LoggerFactory
import kotlin.random.Random
import dev.gvart.genesara.world.internal.death.DeathProcessor

private val log = LoggerFactory.getLogger("dev.gvart.genesara.world.body.internal.death.DeathSweep")

/**
 * Post-passives death sweep. Finds every positioned agent whose body just hit `hp <= 0`
 * (starvation today; combat-driven hp=0 routes through [DeathProcessor.applyDeath]
 * inline from the attack reducer) and routes each one through the same
 * [DeathProcessor] with `cause = null`. The processor handles the penalty + drop
 * roll + position removal + event emission.
 *
 * Called once per tick from `WorldTickHandler` after passives apply but before the
 * per-command reducers, so a dying agent's queued actions hit `NotInWorld` rather
 * than slipping through one tick of post-mortem play.
 */
fun processDeaths(
    state: WorldState,
    deathProcessor: DeathProcessor,
    tick: Long,
    rng: Random = Random.Default,
): Pair<WorldState, List<WorldEvent>> {
    val dying = collectDying(state)
    if (dying.isEmpty()) return state to emptyList()

    val events = mutableListOf<WorldEvent>()
    var nextState = state
    for ((agentId, deathNode) in dying) {
        val (after, deathEvents) = deathProcessor.applyDeath(
            state = nextState,
            agentId = agentId,
            deathNode = deathNode,
            cause = null,
            tick = tick,
            rng = rng,
        )
        if (deathEvents.isEmpty()) {
            log.warn("death sweep: positioned agent {} missing from registry — clearing position", agentId.id)
        }
        nextState = after
        events += deathEvents
    }

    return nextState to events
}

/**
 * Sorted by agent id for deterministic event fan-out — Phase 2 combat will routinely
 * land multiple deaths per tick. `<= 0` rather than `== 0` accepts overkill damage from
 * combat without revisiting the predicate later (starvation alone only zeroes HP).
 */
private fun collectDying(state: WorldState): List<Pair<AgentId, NodeId>> =
    state.positions.entries
        .mapNotNull { (id, nodeId) ->
            val body = state.bodies[id] ?: return@mapNotNull null
            if (body.hp <= 0) id to nodeId else null
        }
        .sortedBy { it.first.id }
