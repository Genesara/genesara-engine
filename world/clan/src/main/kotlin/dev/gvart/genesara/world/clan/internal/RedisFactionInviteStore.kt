package dev.gvart.genesara.world.clan.internal

import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.FactionId
import dev.gvart.genesara.world.FactionInvite
import dev.gvart.genesara.world.FactionInviteId
import dev.gvart.genesara.world.FactionInviteStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Redis-backed [FactionInviteStore].
 *
 * ```
 * Key: faction_invite:{inviteId}      HASH  { faction, targetClan, sentAt, expiresAt }
 *                                     EXPIRE = factionInviteTtlSeconds
 * Key: clan:{targetClanId}:faction_invites SET inviteIds (index)
 * ```
 *
 * The index does not auto-prune; [findByTargetClan] iterates it, looks up each live key, and
 * lazily SREMs entries whose key has expired.
 */
@Component
internal class RedisFactionInviteStore(
    private val redis: StringRedisTemplate,
) : FactionInviteStore {

    private val hash get() = redis.opsForHash<String, String>()
    private val set get() = redis.opsForSet()

    override fun create(invite: FactionInvite, ttlSeconds: Long) {
        val key = inviteKey(invite.inviteId)
        hash.putAll(
            key,
            mapOf(
                FIELD_FACTION to invite.factionId.value.toString(),
                FIELD_TARGET_CLAN to invite.targetClanId.value.toString(),
                FIELD_SENT_AT to invite.sentAtTick.toString(),
                FIELD_EXPIRES_AT to invite.expiresAtTick.toString(),
            ),
        )
        redis.expire(key, Duration.ofSeconds(ttlSeconds))
        set.add(targetIndexKey(invite.targetClanId), invite.inviteId.value.toString())
    }

    override fun find(inviteId: FactionInviteId): FactionInvite? {
        val entries = hash.entries(inviteKey(inviteId))
        if (entries.isEmpty()) return null
        return FactionInvite(
            inviteId = inviteId,
            factionId = entries[FIELD_FACTION]?.let { FactionId(UUID.fromString(it)) } ?: return null,
            targetClanId = entries[FIELD_TARGET_CLAN]?.let { ClanId(UUID.fromString(it)) } ?: return null,
            sentAtTick = entries[FIELD_SENT_AT]?.toLongOrNull() ?: return null,
            expiresAtTick = entries[FIELD_EXPIRES_AT]?.toLongOrNull() ?: return null,
        )
    }

    override fun findByTargetClan(targetClanId: ClanId): List<FactionInvite> {
        val indexKey = targetIndexKey(targetClanId)
        val rawIds = set.members(indexKey) ?: return emptyList()
        if (rawIds.isEmpty()) return emptyList()
        val live = mutableListOf<FactionInvite>()
        for (raw in rawIds) {
            val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
            val invite = find(FactionInviteId(parsed))
            if (invite == null) set.remove(indexKey, raw) else live += invite
        }
        return live
    }

    override fun delete(inviteId: FactionInviteId) {
        val invite = find(inviteId)
        redis.delete(inviteKey(inviteId))
        if (invite != null) set.remove(targetIndexKey(invite.targetClanId), inviteId.value.toString())
    }

    private fun inviteKey(inviteId: FactionInviteId): String = "faction_invite:${inviteId.value}"
    private fun targetIndexKey(targetClanId: ClanId): String = "clan:${targetClanId.value}:faction_invites"

    private companion object {
        const val FIELD_FACTION = "faction"
        const val FIELD_TARGET_CLAN = "targetClan"
        const val FIELD_SENT_AT = "sentAt"
        const val FIELD_EXPIRES_AT = "expiresAt"
    }
}
