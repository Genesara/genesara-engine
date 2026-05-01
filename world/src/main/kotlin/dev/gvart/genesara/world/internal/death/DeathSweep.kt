package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.gvart.genesara.world.internal.death.DeathSweep")

/**
 * Post-passives death sweep. Finds every positioned agent whose body just hit `hp <= 0`
 * (starvation today; combat in Phase 2 will land the same path) and:
 *
 *  1. Applies the canonical death penalty via [AgentRegistry.applyDeathPenalty]
 *     (partial-bar XP loss vs. empty-bar de-level + stat point — the registry decides).
 *  2. Removes the agent from `state.positions` so they're "awaiting respawn". The body
 *     persists at HP=0; the agent must call `respawn` to materialize.
 *  3. Emits [WorldEvent.AgentDied] carrying the penalty summary so the agent learns
 *     what their death cost without a follow-up read.
 *
 * Body restoration happens inside the respawn reducer, not here. The split lets the
 * agent linger at the death state and forces an explicit re-entry rather than auto-
 * spawning them somewhere they didn't choose.
 *
 * Called once per tick from `WorldTickHandler` after passives apply but before the
 * per-command reducers, so a dying agent's queued actions hit `NotInWorld` rather than
 * slipping through one tick of post-mortem play.
 */
internal fun processDeaths(
    state: WorldState,
    balance: BalanceLookup,
    agents: AgentRegistry,
    tick: Long,
): Pair<WorldState, List<WorldEvent.AgentDied>> {
    val dying = collectDying(state)
    if (dying.isEmpty()) return state to emptyList()

    val xpLoss = balance.xpLossOnDeath()
    val events = mutableListOf<WorldEvent.AgentDied>()
    var nextPositions = state.positions
    for ((agentId, deathNode) in dying) {
        val outcome = agents.applyDeathPenalty(agentId, xpLoss)
        if (outcome == null) {
            log.warn("death sweep: positioned agent {} missing from registry — clearing position", agentId.id)
            nextPositions = nextPositions - agentId
            continue
        }
        nextPositions = nextPositions - agentId
        events += buildDeathEvent(agentId, deathNode, outcome, tick)
    }

    return state.copy(positions = nextPositions) to events
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

private fun buildDeathEvent(
    agentId: AgentId,
    deathNode: NodeId,
    outcome: DeathPenaltyOutcome,
    tick: Long,
): WorldEvent.AgentDied = WorldEvent.AgentDied(
    agent = agentId,
    at = deathNode,
    xpLost = outcome.xpLost,
    deleveled = outcome.deleveled,
    attributePointLost = outcome.attributePointLost?.let { loss ->
        when (loss) {
            is AttributePointLoss.Unspent -> "UNSPENT"
            is AttributePointLoss.Allocated -> loss.attribute.name
        }
    },
    tick = tick,
    // TODO(combat): route the killing-attack's commandId once Phase 2 lands.
    causedBy = null,
)
