package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawnHook
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect.MaybeSpawnLazyNpcs
import kotlin.random.Random

/**
 * Effect-applier overload used by reducers that emit [MaybeSpawnLazyNpcs]: it
 * mutates slices for the simple effects (as the primary overload) AND invokes
 * the environment zone's [LazyNpcSpawnHook] for each lazy-spawn marker, folding
 * spawn events into the returned list.
 *
 * Sits in the umbrella `:world` module because it imports the environment-zone
 * `LazyNpcSpawnHook` — the simple `applyEffects(effects)` overload remains in
 * `:world-core`.
 */
fun WorldState.applyEffects(
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
