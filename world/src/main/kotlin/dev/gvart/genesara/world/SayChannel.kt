package dev.gvart.genesara.world

/**
 * Routing channel of a [dev.gvart.genesara.world.commands.WorldCommand.Say]. v1 ships
 * [LOCAL] only — clan and trade channels land alongside Phase 3 clans and trade
 * infrastructure (issues #22 / #13) by extending this enum and the reducer's recipient
 * resolution.
 */
enum class SayChannel {
    LOCAL,
}
