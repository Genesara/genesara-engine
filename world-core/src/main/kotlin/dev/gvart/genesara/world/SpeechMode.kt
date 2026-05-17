package dev.gvart.genesara.world

import dev.gvart.genesara.world.commands.CoreCommand

/**
 * Volume of a [dev.gvart.genesara.world.commands.CoreCommand.Say]. The reducer maps
 * this to a node-hop radius via [dev.gvart.genesara.world.internal.balance.BalanceLookup.sayRangeFor]
 * — wider modes reach further listeners. Default at the API edge is [NORMAL].
 */
enum class SpeechMode {
    WHISPER,
    NORMAL,
    SCREAM,
}
