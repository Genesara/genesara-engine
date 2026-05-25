package dev.gvart.genesara.world.social.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyStore
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Bridges [PartyReadView] (consumed by combat) to [PartyStore] (owned by social).
 *
 * The [partyIdOf] fast path bypasses [PartyStore.findByAgent] to skip the full
 * member-load — combat calls this once per attack to check the formation buff
 * predicate, so a single `GET` keeps the hot path cheap.
 */
@Component
internal class PartyReadViewImpl(
    private val redis: StringRedisTemplate,
    private val partyStore: PartyStore,
) : PartyReadView {

    private val value get() = redis.opsForValue()

    override fun partyOf(agentId: AgentId): Party? = partyStore.findByAgent(agentId)

    override fun partyIdOf(agentId: AgentId): PartyId? {
        val raw = value.get("agent:${agentId.id}:party") ?: return null
        return runCatching { PartyId(UUID.fromString(raw)) }.getOrNull()
    }

    override fun find(partyId: PartyId): Party? = partyStore.find(partyId)
}
