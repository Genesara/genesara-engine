package dev.gvart.genesara.world

import dev.gvart.genesara.world.commands.CoreCommand

/**
 * Routing channel of a [dev.gvart.genesara.world.commands.CoreCommand.Say]. v1 ships
 * [LOCAL] only — clan and trade channels land alongside Phase 3 clans and trade
 * infrastructure (issues #22 / #13) by extending this enum and the reducer's recipient
 * resolution.
 */
enum class SayChannel {
    LOCAL,
}
