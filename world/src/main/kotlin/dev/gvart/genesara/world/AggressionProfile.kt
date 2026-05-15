package dev.gvart.genesara.world

/**
 * NPC behavior posture, evaluated by `applyNpcAi` per tick.
 *
 * - [PASSIVE]: never initiates; flees one node away on damage.
 * - [TERRITORIAL]: behaves like [HOSTILE] within `territoryRadius` hops of `spawnNodeId`; inert outside.
 * - [HOSTILE]: attacks any agent within weapon range every `attackIntervalTicks`.
 *
 * `WANDERING` deferred — Tier-A v1 ships these three.
 */
enum class AggressionProfile {
    PASSIVE,
    TERRITORIAL,
    HOSTILE,
}
