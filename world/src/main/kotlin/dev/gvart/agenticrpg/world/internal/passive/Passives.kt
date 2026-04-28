package dev.gvart.agenticrpg.world.internal.passive

import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.BodyDelta
import dev.gvart.agenticrpg.world.events.WorldEvent
import dev.gvart.agenticrpg.world.internal.balance.BalanceLookup
import dev.gvart.agenticrpg.world.internal.worldstate.WorldState

internal fun interface Passive {
    fun deltasFor(state: WorldState, balance: BalanceLookup): Map<AgentId, BodyDelta>
}

internal val staminaRegenPassive = Passive { state, balance ->
    state.bodies.mapNotNull { (id, _) ->
        val nodeId = state.positions[id] ?: return@mapNotNull null
        val node = state.nodes[nodeId] ?: return@mapNotNull null
        val region = state.regions[node.regionId] ?: return@mapNotNull null
        val climate = region.climate ?: return@mapNotNull null
        val regen = balance.staminaRegenPerTick(climate)
        if (regen == 0) null else id to BodyDelta(stamina = regen)
    }.toMap()
}

internal fun applyPassives(
    state: WorldState,
    balance: BalanceLookup,
    tick: Long,
    passives: List<Passive> = listOf(staminaRegenPassive),
): Pair<WorldState, WorldEvent.PassivesApplied?> {
    val desired: Map<AgentId, BodyDelta> = passives
        .flatMap { it.deltasFor(state, balance).entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, deltas) -> deltas.fold(BodyDelta()) { acc, d -> acc + d } }

    if (desired.isEmpty()) return state to null

    val applied = mutableMapOf<AgentId, BodyDelta>()
    val nextBodies = state.bodies.toMutableMap()
    for ((id, want) in desired) {
        val body = nextBodies[id] ?: continue
        val newHp = (body.hp + want.hp).coerceIn(0, body.maxHp)
        val newSt = (body.stamina + want.stamina).coerceIn(0, body.maxStamina)
        val newMn = (body.mana + want.mana).coerceIn(0, body.maxMana)
        val realDelta = BodyDelta(
            hp = newHp - body.hp,
            stamina = newSt - body.stamina,
            mana = newMn - body.mana,
        )
        if (realDelta.isZero) continue
        nextBodies[id] = body.copy(hp = newHp, stamina = newSt, mana = newMn)
        applied[id] = realDelta
    }

    if (applied.isEmpty()) return state to null

    val nextState = state.copy(bodies = nextBodies)
    val event = WorldEvent.PassivesApplied(applied, tick)
    return nextState to event
}