package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.npc.LazyNpcSpawnHook
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.AddSpawnedNpc
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.IncrementKillStreak
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.MaybeSpawnLazyNpcs
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.RemoveNpc
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.RemovePosition
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.SetPosition
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.UpdateBody
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.UpdateInventory
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.UpdateKillStreak
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.UpdateNpc
import kotlin.random.Random

/**
 * Single-hop effect applier (ADR 0003 §C3).
 *
 * Each effect mutates exactly the slice its target zone owns. Effects do not
 * emit further effects — cross-zone cascades compose via shared math helpers
 * in zone public surfaces, called by the *initiating* reducer.
 *
 * Ordering: when a reducer returns `(sliceDelta, effects)`, the dispatcher
 * applies `sliceDelta` first (so the reducer's own writes land), then folds
 * the effects through this function (so cross-zone writes land afterward).
 */
internal fun WorldState.applyEffects(effects: List<CrossZoneEffect>): WorldState =
    effects.fold(this) { state, effect -> state.apply(effect) }

/**
 * Effect-applier overload used by reducers that emit [MaybeSpawnLazyNpcs]: it
 * mutates slices for the simple effects (as the primary overload) AND invokes
 * the environment zone's [LazyNpcSpawnHook] for each lazy-spawn marker, folding
 * spawn events into the returned list.
 *
 * Sits in `worldstate/` (destined for the umbrella in Phase 2) rather than
 * `movement/` so MovementReducer no longer imports the environment zone's
 * `LazyNpcSpawnHook` directly.
 */
internal fun WorldState.applyEffects(
    effects: List<CrossZoneEffect>,
    lazyNpcSpawn: LazyNpcSpawnHook,
    rng: Random,
): Pair<WorldState, List<WorldEvent>> {
    var state = this
    val events = mutableListOf<WorldEvent>()
    for (effect in effects) {
        if (effect is MaybeSpawnLazyNpcs) {
            val (next, spawnEvents) = lazyNpcSpawn.maybeSeed(state, effect.destination, effect.agent, effect.tick, rng)
            state = next
            events += spawnEvents
        } else {
            state = state.apply(effect)
        }
    }
    return state to events
}

private fun WorldState.apply(effect: CrossZoneEffect): WorldState = when (effect) {
    is UpdateBody -> updateBody(effect.agent, effect.body)
    is UpdateInventory -> updateInventory(effect.agent, effect.inventory)
    is SetPosition -> moveAgent(effect.agent, effect.node)
    is RemovePosition -> copy(core = core.copy(positions = core.positions - effect.agent))
    is IncrementKillStreak -> incrementKillStreak(effect.agent, effect.currentTick, effect.windowTicks)
    is UpdateKillStreak -> updateKillStreak(effect.agent, effect.streak)
    is UpdateNpc -> updateNpc(effect.npc)
    is RemoveNpc -> removeNpc(effect.npcId, effect.tick)
    is AddSpawnedNpc -> addSpawnedNpc(effect.npc)
    is MaybeSpawnLazyNpcs -> this
}
