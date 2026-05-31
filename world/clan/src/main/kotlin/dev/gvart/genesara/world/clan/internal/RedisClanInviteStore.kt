package dev.gvart.genesara.world.clan.internal

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanInvite
import dev.gvart.genesara.world.ClanInviteId
import dev.gvart.genesara.world.ClanInviteStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Redis-backed [ClanInviteStore].
 *
 * ```
 * Key: clan_invite:{inviteId}   HASH  { clan, inviter, invitee, sentAt, expiresAt }
 *                               EXPIRE = clanInviteTtlSeconds
 * Key: clan:{clanId}:invites    SET   inviteIds (index)
 * ```
 *
 * The clan index does not auto-prune when invite keys expire; [findByClan] iterates the
 * index, looks up each live invite key, and lazily SREMs entries whose key is gone.
 */
@Component
internal class RedisClanInviteStore(
    private val redis: StringRedisTemplate,
) : ClanInviteStore {

    private val hash get() = redis.opsForHash<String, String>()
    private val set get() = redis.opsForSet()

    override fun create(invite: ClanInvite, ttlSeconds: Long) {
        val key = inviteKey(invite.inviteId)
        hash.putAll(
            key,
            mapOf(
                FIELD_CLAN to invite.clanId.value.toString(),
                FIELD_INVITER to invite.inviterId.id.toString(),
                FIELD_INVITEE to invite.inviteeId.id.toString(),
                FIELD_SENT_AT to invite.sentAtTick.toString(),
                FIELD_EXPIRES_AT to invite.expiresAtTick.toString(),
            ),
        )
        redis.expire(key, Duration.ofSeconds(ttlSeconds))
        set.add(clanIndexKey(invite.clanId), invite.inviteId.value.toString())
    }

    override fun find(inviteId: ClanInviteId): ClanInvite? {
        val entries = hash.entries(inviteKey(inviteId))
        if (entries.isEmpty()) return null
        return ClanInvite(
            inviteId = inviteId,
            clanId = entries[FIELD_CLAN]?.let { ClanId(UUID.fromString(it)) } ?: return null,
            inviterId = entries[FIELD_INVITER]?.let { AgentId(UUID.fromString(it)) } ?: return null,
            inviteeId = entries[FIELD_INVITEE]?.let { AgentId(UUID.fromString(it)) } ?: return null,
            sentAtTick = entries[FIELD_SENT_AT]?.toLongOrNull() ?: return null,
            expiresAtTick = entries[FIELD_EXPIRES_AT]?.toLongOrNull() ?: return null,
        )
    }

    override fun findByClan(clanId: ClanId): List<ClanInvite> {
        val indexKey = clanIndexKey(clanId)
        val rawIds = set.members(indexKey) ?: return emptyList()
        if (rawIds.isEmpty()) return emptyList()
        val live = mutableListOf<ClanInvite>()
        for (raw in rawIds) {
            val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
            val invite = find(ClanInviteId(parsed))
            if (invite == null) set.remove(indexKey, raw) else live += invite
        }
        return live
    }

    override fun delete(inviteId: ClanInviteId) {
        val invite = find(inviteId)
        redis.delete(inviteKey(inviteId))
        if (invite != null) set.remove(clanIndexKey(invite.clanId), inviteId.value.toString())
    }

    private fun inviteKey(inviteId: ClanInviteId): String = "clan_invite:${inviteId.value}"
    private fun clanIndexKey(clanId: ClanId): String = "clan:${clanId.value}:invites"

    private companion object {
        const val FIELD_CLAN = "clan"
        const val FIELD_INVITER = "inviter"
        const val FIELD_INVITEE = "invitee"
        const val FIELD_SENT_AT = "sentAt"
        const val FIELD_EXPIRES_AT = "expiresAt"
    }
}
