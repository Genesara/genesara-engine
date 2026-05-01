package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.gvart.genesara.world.internal.death.DeathSweep")

/**
 * Post-passives death sweep. Finds every positioned agent whose body just hit
 * `hp == 0` (typically from the [starvationDamagePassive][dev.gvart.genesara.world.internal.passive.starvationDamagePassive],
 * but combat reducers in Phase 2 will land at the same spot via the same HP=0
 * path) and:
 *
 *  1. Applies the canonical death penalty via [AgentRegistry.applyDeathPenalty]
 *     (partial-bar XP loss vs. empty-bar de-level + stat point — the registry
 *     decides which branch fires and reports back).
 *  2. Removes the agent from `state.positions` so they're now "awaiting
 *     respawn". The body persists at HP=0 — the agent must call the `respawn`
 *     MCP tool to materialize.
 *  3. Emits a [WorldEvent.AgentDied] carrying the penalty summary so the agent
 *     learns what their death cost without a follow-up read.
 *
 * The body is **not** restored here. Resetting HP/Stamina/Mana/gauges happens
 * inside the respawn reducer when the agent calls back. This split lets the
 * agent linger at the death state (interrogate `get_status` / `inspect` to see
 * they're at 0 HP) and forces an explicit re-entry rather than auto-spawning
 * them somewhere they didn't choose.
 *
 * **Re-entry via this sweep is naturally idempotent.** An already-dead agent
 * is removed from `state.positions` and won't re-trigger the next tick.
 *
 * The function is internal to the death package and called once per tick from
 * [WorldTickHandler][dev.gvart.genesara.world.internal.tick.WorldTickHandler]
 * after passives apply but before the per-command reducers run. Running it
 * before commands ensures a dying agent's queued actions for this tick get
 * rejected with the existing `NotInWorld` rejection rather than slipping
 * through one tick of post-mortem play.
 */
internal fun processDeaths(
    state: WorldState,
    balance: BalanceLookup,
    agents: AgentRegistry,
    tick: Long,
): Pair<WorldState, List<WorldEvent.AgentDied>> {
    // Collect the (agentId, deathNode) pairs in a deterministic order so the
    // emitted events fan out by agent-id ordering. Two agents dying on the
    // same tick is rare today but combat in Phase 2 will make it routine.
    val dying = state.positions.entries
        .mapNotNull { (id, nodeId) ->
            val body = state.bodies[id] ?: return@mapNotNull null
            // `<= 0` rather than `== 0`: starvation only ever zeroes HP (the
            // passive clamps), but combat in Phase 2 will produce overkill
            // damage that pushes HP negative. Treating both as dead avoids a
            // future "fix this back to == 0" regression.
            if (body.hp <= 0) id to nodeId else null
        }
        .sortedBy { it.first.id }
    if (dying.isEmpty()) return state to emptyList()

    val xpLoss = balance.xpLossOnDeath()
    val events = mutableListOf<WorldEvent.AgentDied>()
    var nextPositions = state.positions
    for ((agentId, deathNode) in dying) {
        val outcome = agents.applyDeathPenalty(agentId, xpLoss)
        if (outcome == null) {
            // State corruption: positioned agent isn't in the registry. Log and
            // still remove from positions so the same row doesn't trip the
            // sweep on every tick.
            log.warn("death sweep: positioned agent {} missing from registry — clearing position", agentId.id)
            nextPositions = nextPositions - agentId
            continue
        }

        nextPositions = nextPositions - agentId
        events += WorldEvent.AgentDied(
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
            // Starvation deaths have no causing command in v1. Combat in
            // Phase 2 will route the killing-attack's commandId through here.
            causedBy = null,
        )
    }

    return state.copy(positions = nextPositions) to events
}
