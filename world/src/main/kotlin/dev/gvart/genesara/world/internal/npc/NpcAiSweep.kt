package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.worldstate.WorldState
import kotlin.random.Random
import org.springframework.stereotype.Component

/**
 * Per-tick NPC AI sweep — runs after passives + death sweep, before per-command
 * reducers (Q3β, Q15b). Each loaded NPC gets one decision per tick:
 *
 *  - HOSTILE: pick the nearest agent within `def.range` hops; if any exists and
 *    `last_attack_tick + attack_interval ≤ tick`, attack them.
 *  - TERRITORIAL: behave HOSTILE if the spawn node is within `territory_radius`
 *    hops of the candidate's path (we approximate with "the NPC's current node
 *    is still within `territory_radius` of `spawn_node_id`"); otherwise inert.
 *  - PASSIVE: never initiates here — flee is event-driven inside
 *    [reduceAttackNpc].
 *
 * Damage is applied directly to the target's [WorldState.bodies] entry; no
 * crit on the NPC side per Q7b. If HP hits zero, the death is routed through
 * [DeathProcessor.applyDeath] inline so the existing penalty + drop + position
 * removal pipeline runs (mirrors what AttackReducer does for agent-vs-agent
 * killing blows).
 */
@Component
internal class NpcAiSweep(
    private val catalog: NpcCatalog,
    private val balance: BalanceLookup,
    private val agents: AgentRegistry,
    private val deathProcessor: DeathProcessor,
) {
    fun apply(
        state: WorldState,
        tick: Long,
        rng: Random = Random.Default,
    ): Pair<WorldState, List<WorldEvent>> {
        if (state.npcs.isEmpty() || state.positions.isEmpty()) return state to emptyList()

        val agentNodes: Map<NodeId, List<AgentId>> = state.positions.entries
            .groupBy({ it.value }, { it.key })

        val events = mutableListOf<WorldEvent>()
        var nextState = state
        // Iterate a snapshot — the reducer mutates the npc map.
        val snapshot = state.npcs.values.sortedBy { it.id.value }
        for (npc in snapshot) {
            val def = catalog.byType(npc.type) ?: continue
            if (def.aggressionProfile == AggressionProfile.PASSIVE) continue
            if (tick - npc.lastAttackTick < def.attackIntervalTicks) continue
            if (def.aggressionProfile == AggressionProfile.TERRITORIAL) {
                val distFromSpawn = hopDistance(nextState, npc.nodeId, npc.spawnNodeId, def.territoryRadius)
                if (distFromSpawn < 0) continue
            }

            // Pick the nearest agent within def.range, breaking ties by AgentId
            // for a deterministic event order.
            val target = pickTarget(nextState, npc, def, agentNodes) ?: continue

            val (after, attackEvents) = swing(nextState, npc, def, target, tick, rng)
            nextState = after
            events += attackEvents
        }

        return nextState to events
    }

    private fun pickTarget(
        state: WorldState,
        npc: Npc,
        def: NpcDef,
        agentNodes: Map<NodeId, List<AgentId>>,
    ): AgentId? {
        var best: AgentId? = null
        var bestDist = Int.MAX_VALUE
        for ((node, ids) in agentNodes) {
            val d = hopDistance(state, npc.nodeId, node, def.range)
            if (d < 0) continue
            for (id in ids.sortedBy { it.id }) {
                val body = state.bodies[id] ?: continue
                if (body.hp <= 0) continue
                val currentBest = best
                if (d < bestDist || (d == bestDist && (currentBest == null || id.id < currentBest.id))) {
                    best = id
                    bestDist = d
                }
            }
        }
        return best
    }

    private fun swing(
        state: WorldState,
        npc: Npc,
        def: NpcDef,
        target: AgentId,
        tick: Long,
        rng: Random,
    ): Pair<WorldState, List<WorldEvent>> {
        val targetBody = state.bodies[target] ?: return state to emptyList()
        val targetAgent = agents.find(target)

        val dexBased = balance.dodgeChancePercent(targetAgent?.attributes?.dexterity ?: 0)
        val isDodged = dexBased > 0 && rng.nextInt(100) < dexBased
        val raw = (def.damage * balance.damageTypeModifier(def.damageType)).toInt().coerceAtLeast(0)
        // Defender mitigation against an NPC attacker: simple constant for now;
        // skip armorDef integration to keep the NPC math honest with Q7a's flat-only stance.
        val hpLost = if (isDodged) 0 else raw
        val nextTargetBody = targetBody.takeDamage(hpLost)
        var nextState = state.updateBody(target, nextTargetBody)
            .updateNpc(npc.withAttackTick(tick))

        val killed = nextTargetBody.hp == 0
        val events = mutableListOf<WorldEvent>()
        events += CombatEvent.NpcAttackedAgent(
            npc = npc.id,
            npcType = npc.type,
            target = target,
            at = npc.nodeId,
            damageType = def.damageType,
            baseDamage = raw,
            hpLost = hpLost,
            isDodged = isDodged,
            targetHpAfter = nextTargetBody.hp,
            targetKilled = killed,
            tick = tick,
        )

        if (killed) {
            val targetNode = state.positions[target] ?: npc.nodeId
            // cause = null routes through DeathProcessor's starvation-style path
            // (no kill-streak credit, AgentDied.causedBy = null). Correct
            // semantic: an NPC kill is not an agent kill — no streak rewards.
            val (afterDeath, deathEvents) = deathProcessor.applyDeath(
                state = nextState,
                agentId = target,
                deathNode = targetNode,
                cause = null,
                tick = tick,
                rng = rng,
            )
            nextState = afterDeath
            events += deathEvents
        }
        return nextState to events
    }
}
