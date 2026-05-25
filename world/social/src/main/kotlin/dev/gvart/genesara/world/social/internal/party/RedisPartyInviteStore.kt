package dev.gvart.genesara.world.social.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.PartyInvite
import dev.gvart.genesara.world.PartyInviteId
import dev.gvart.genesara.world.PartyInviteStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Redis-backed [PartyInviteStore].
 *
 * ```
 * Key: invite:{inviteId}              HASH  { inviterId, inviteeId, sentAtTick, expiresAtTick }
 *                                     EXPIRE = partyInviteTtlSeconds
 * Key: agent:{inviteeId}:invites      SET   inviteIds (index)
 * Key: agent:{inviterId}:invites_sent SET   inviteIds (index)
 * ```
 *
 * Indexes do not auto-prune when invite keys expire. [findByInvitee] and
 * [findByInviter] iterate their index, look up the live invite key for each id,
 * and lazily SREM entries whose underlying key is gone.
 */
@Component
internal class RedisPartyInviteStore(
    private val redis: StringRedisTemplate,
) : PartyInviteStore {

    private val hash get() = redis.opsForHash<String, String>()
    private val set get() = redis.opsForSet()

    override fun create(invite: PartyInvite, ttlSeconds: Long) {
        val inviteKey = inviteKey(invite.inviteId)
        hash.putAll(
            inviteKey,
            mapOf(
                FIELD_INVITER to invite.inviterId.id.toString(),
                FIELD_INVITEE to invite.inviteeId.id.toString(),
                FIELD_SENT_AT to invite.sentAtTick.toString(),
                FIELD_EXPIRES_AT to invite.expiresAtTick.toString(),
            ),
        )
        redis.expire(inviteKey, Duration.ofSeconds(ttlSeconds))
        set.add(inviteeIndexKey(invite.inviteeId), invite.inviteId.value.toString())
        set.add(inviterIndexKey(invite.inviterId), invite.inviteId.value.toString())
    }

    override fun find(inviteId: PartyInviteId): PartyInvite? {
        val entries = hash.entries(inviteKey(inviteId))
        if (entries.isEmpty()) return null
        return PartyInvite(
            inviteId = inviteId,
            inviterId = entries[FIELD_INVITER]?.let { AgentId(UUID.fromString(it)) } ?: return null,
            inviteeId = entries[FIELD_INVITEE]?.let { AgentId(UUID.fromString(it)) } ?: return null,
            sentAtTick = entries[FIELD_SENT_AT]?.toLongOrNull() ?: return null,
            expiresAtTick = entries[FIELD_EXPIRES_AT]?.toLongOrNull() ?: return null,
        )
    }

    override fun findByInvitee(inviteeId: AgentId): List<PartyInvite> =
        liveByIndex(inviteeIndexKey(inviteeId))

    override fun findByInviter(inviterId: AgentId): List<PartyInvite> =
        liveByIndex(inviterIndexKey(inviterId))

    override fun delete(inviteId: PartyInviteId) {
        val invite = find(inviteId)
        redis.delete(inviteKey(inviteId))
        if (invite != null) {
            set.remove(inviteeIndexKey(invite.inviteeId), inviteId.value.toString())
            set.remove(inviterIndexKey(invite.inviterId), inviteId.value.toString())
        }
    }

    override fun deleteAllByInviter(inviterId: AgentId): List<PartyInvite> {
        val invites = findByInviter(inviterId)
        for (invite in invites) {
            redis.delete(inviteKey(invite.inviteId))
            set.remove(inviteeIndexKey(invite.inviteeId), invite.inviteId.value.toString())
        }
        redis.delete(inviterIndexKey(inviterId))
        return invites
    }

    private fun liveByIndex(indexKey: String): List<PartyInvite> {
        val rawIds = set.members(indexKey) ?: return emptyList()
        if (rawIds.isEmpty()) return emptyList()
        val live = mutableListOf<PartyInvite>()
        for (raw in rawIds) {
            val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
            val invite = find(PartyInviteId(parsed))
            if (invite == null) {
                set.remove(indexKey, raw)
            } else {
                live += invite
            }
        }
        return live
    }

    private fun inviteKey(inviteId: PartyInviteId): String = "invite:${inviteId.value}"
    private fun inviteeIndexKey(inviteeId: AgentId): String = "agent:${inviteeId.id}:invites"
    private fun inviterIndexKey(inviterId: AgentId): String = "agent:${inviterId.id}:invites_sent"

    private companion object {
        const val FIELD_INVITER = "inviter"
        const val FIELD_INVITEE = "invitee"
        const val FIELD_SENT_AT = "sentAt"
        const val FIELD_EXPIRES_AT = "expiresAt"
    }
}
