package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.world.events.WorldEvent

/**
 * Output of a zone reducer (ADR 0003 §P4).
 *
 * - [sliceDelta]: the (possibly unchanged) state of the reducer's owning zone after
 *   the command. Returned even when nothing mutated so the dispatcher's apply path is
 *   uniform — same-reference returns are a no-op `copy`.
 * - [effects]: typed writes targeting *other* zones, applied single-hop by their
 *   owning effect handlers (Phase 1.3).
 * - [events]: external [WorldEvent]s emitted by this command, published on the bus
 *   as today.
 */
data class ReducerOutput<S>(
    val sliceDelta: S,
    val effects: List<CrossZoneEffect> = emptyList(),
    val events: List<WorldEvent> = emptyList(),
)
