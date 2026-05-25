package dev.gvart.genesara.world.internal.worldstate.views

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId

/**
 * Typed read access to party state. Consumed by zones outside `:world:social` that
 * need to read membership without taking a hard dependency on the store impl —
 * combat reads it for the XP-split fan-out and the formation buff.
 *
 * Reads route to Redis, so call counts matter on hot paths. Combat amortizes the
 * lookup to the kill-blow tick (rare) and the per-attack formation check (cheap
 * single string GET via `partyIdOf`).
 */
interface PartyReadView {
    /** Full party state for [agentId], or null when the agent is not in any party. */
    fun partyOf(agentId: AgentId): Party?

    /** Cheap pointer lookup that does not load the full member roster. */
    fun partyIdOf(agentId: AgentId): PartyId?

    fun find(partyId: PartyId): Party?

    companion object {
        val NoOp: PartyReadView = object : PartyReadView {
            override fun partyOf(agentId: AgentId): Party? = null
            override fun partyIdOf(agentId: AgentId): PartyId? = null
            override fun find(partyId: PartyId): Party? = null
        }
    }
}
