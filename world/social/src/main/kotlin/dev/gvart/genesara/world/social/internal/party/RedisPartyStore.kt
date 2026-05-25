package dev.gvart.genesara.world.social.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.PartyStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Redis-backed [PartyStore].
 *
 * ```
 * Key:   party:{partyId}             HASH  { leader: agentId, formedAtTick: long }
 * Key:   party:{partyId}:members     ZSET  agentIds scored by joinedAtTick
 * Key:   agent:{agentId}:party       STRING partyId
 * ```
 *
 * Parties are ephemeral — no AOF / RDB durability guarantee. Engine restart drops
 * every party silently (design Q11).
 *
 * Concurrency: per-agent serialization via [dev.gvart.genesara.world.internal.tick.RedisCommandQueue]
 * is the only race protection. The store performs multi-key writes as plain
 * sequential calls — partial failure mid-write would leave a half-applied party,
 * which is acceptable given the ephemeral contract.
 */
@Component
internal class RedisPartyStore(
    private val redis: StringRedisTemplate,
) : PartyStore {

    private val hash get() = redis.opsForHash<String, String>()
    private val zset get() = redis.opsForZSet()
    private val value get() = redis.opsForValue()

    override fun create(party: Party) {
        val partyKey = partyKey(party.partyId)
        hash.putAll(
            partyKey,
            mapOf(
                FIELD_LEADER to party.leaderId.id.toString(),
                FIELD_FORMED_AT to party.formedAtTick.toString(),
            ),
        )
        val membersKey = membersKey(party.partyId)
        redis.delete(membersKey)
        for (member in party.members) {
            zset.add(membersKey, member.agentId.id.toString(), member.joinedAtTick.toDouble())
            value.set(agentPartyKey(member.agentId), party.partyId.value.toString())
        }
    }

    override fun find(partyId: PartyId): Party? {
        val partyKey = partyKey(partyId)
        val entries = hash.entries(partyKey)
        if (entries.isEmpty()) return null
        val leader = entries[FIELD_LEADER]?.let { AgentId(UUID.fromString(it)) } ?: return null
        val formedAt = entries[FIELD_FORMED_AT]?.toLongOrNull() ?: return null
        val members = loadMembers(partyId)
        if (members.isEmpty()) return null
        return Party(partyId = partyId, leaderId = leader, members = members, formedAtTick = formedAt)
    }

    override fun findByAgent(agentId: AgentId): Party? {
        val partyIdStr = value.get(agentPartyKey(agentId)) ?: return null
        val partyId = runCatching { PartyId(UUID.fromString(partyIdStr)) }.getOrNull() ?: return null
        return find(partyId)
    }

    override fun addMember(partyId: PartyId, member: PartyMember): Party? {
        if (find(partyId) == null) return null
        zset.add(membersKey(partyId), member.agentId.id.toString(), member.joinedAtTick.toDouble())
        value.set(agentPartyKey(member.agentId), partyId.value.toString())
        return find(partyId)
    }

    override fun removeMember(partyId: PartyId, agentId: AgentId): Party? {
        val party = find(partyId) ?: return null
        if (!party.contains(agentId)) return null
        zset.remove(membersKey(partyId), agentId.id.toString())
        redis.delete(agentPartyKey(agentId))
        return find(partyId)
    }

    override fun replaceLeader(partyId: PartyId, newLeader: AgentId): Party? {
        val partyKey = partyKey(partyId)
        if (hash.entries(partyKey).isEmpty()) return null
        hash.put(partyKey, FIELD_LEADER, newLeader.id.toString())
        return find(partyId)
    }

    override fun delete(partyId: PartyId) {
        val members = loadMembers(partyId)
        for (member in members) {
            redis.delete(agentPartyKey(member.agentId))
        }
        redis.delete(membersKey(partyId))
        redis.delete(partyKey(partyId))
    }

    private fun loadMembers(partyId: PartyId): List<PartyMember> {
        val membersKey = membersKey(partyId)
        val raw = zset.rangeWithScores(membersKey, 0, -1) ?: return emptyList()
        return raw.mapNotNull { tuple ->
            val agentIdStr = tuple.value ?: return@mapNotNull null
            val score = tuple.score ?: return@mapNotNull null
            val parsed = runCatching { UUID.fromString(agentIdStr) }.getOrNull() ?: return@mapNotNull null
            PartyMember(agentId = AgentId(parsed), joinedAtTick = score.toLong())
        }.sortedWith(compareBy({ it.joinedAtTick }, { it.agentId.id }))
    }

    // TODO(shard-step-3): prefix with `world:{worldId}:` once #80 introduces per-world routing.
    private fun partyKey(partyId: PartyId): String = "party:${partyId.value}"
    private fun membersKey(partyId: PartyId): String = "party:${partyId.value}:members"
    private fun agentPartyKey(agentId: AgentId): String = "agent:${agentId.id}:party"

    private companion object {
        const val FIELD_LEADER = "leader"
        const val FIELD_FORMED_AT = "formedAtTick"
    }
}
