package dev.gvart.genesara.world.events

/**
 * Marker interface for everything the world reducer emits. Non-sealed by design
 * (ADR 0003 §"Commands & events"): variants are grouped into per-zone sealed
 * sub-hierarchies ([CoreEvent], [BodyEvent], [CombatEvent], [EconomyEvent],
 * [EnvironmentEvent]) so each zone owns its slice.
 *
 * No `@JsonTypeInfo` here: WorldEvents are dispatched as concrete subtypes via
 * Spring `@EventListener`s, not deserialized polymorphically off the wire.
 */
interface WorldEvent {
    val tick: Long
}
