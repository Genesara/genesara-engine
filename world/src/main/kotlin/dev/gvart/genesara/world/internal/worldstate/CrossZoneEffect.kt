package dev.gvart.genesara.world.internal.worldstate

/**
 * Typed write surface between zones (ADR 0003 §P4).
 *
 * A reducer in zone X may mutate only its own slice; cross-zone writes go through
 * this sealed hierarchy. Each variant is owned by — and applied by — the *target*
 * zone's effect handler. Phase 1.3 enumerates the variants as reducers migrate to
 * `ReducerOutput`.
 *
 * Effects are single-hop: an effect handler mutates its slice + emits external
 * [dev.gvart.genesara.world.events.WorldEvent]s, but never emits further effects.
 * Cross-zone cascades compose via shared pure math helpers in zone public surfaces,
 * not via effect chaining.
 */
internal sealed interface CrossZoneEffect
