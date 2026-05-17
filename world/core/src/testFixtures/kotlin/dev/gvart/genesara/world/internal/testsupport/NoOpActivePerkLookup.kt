package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.ActivePerk
import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AgentId

/** Default for reducer tests that don't exercise the active-ability surface. */
object NoOpActivePerkLookup : ActivePerkLookup {
    override fun byAbility(agent: AgentId, ability: AbilityId): ActivePerk? = null
}
