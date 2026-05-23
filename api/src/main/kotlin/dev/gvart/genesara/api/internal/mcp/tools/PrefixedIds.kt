package dev.gvart.genesara.api.internal.mcp.tools

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.NpcId
import java.util.UUID

/**
 * Wire-format convention for entity ids that cross the MCP boundary.
 *
 * Underlying storage stays UUID; the prefix lives only in tool requests and
 * responses. Two kinds are recognised today:
 *
 *  - `agent:<uuid>` — an [AgentId] (player character).
 *  - `npc:<uuid>`   — an [NpcId] (Tier-A fauna).
 *
 * Strict parsing: bare UUIDs are rejected. Pre-prod, this gives us a clean
 * type discriminator on the wire from day one — no migration corridor where
 * `<uuid>` and `agent:<uuid>` both work and the convention rots from drift.
 *
 * Non-UUID id kinds (item ids, recipe ids, node ids) are unaffected and stay
 * stringly-typed as before.
 *
 * Follow-up: `building:`, `drop:`, `instance:`, `trade:` for the other entity
 * UUIDs on the wire; per-event Jackson serializer so [WorldEvent] payloads
 * picked up by `CommandRejected` and the (pending) NPC event listeners get
 * the same encoding without each producer remembering to call into here.
 */
internal object PrefixedIds {
    private const val AGENT = "agent:"
    private const val NPC = "npc:"
    private const val MOUNT = "mount:"

    fun encodeAgent(id: AgentId): String = "$AGENT${id.id}"

    /** Convenience for sites that already hold a raw [UUID] known to be an agent id — today only `DroppedItemView.Equipment.creatorAgentId`, which the world layer exposes as a bare UUID. */
    fun encodeAgent(uuid: UUID): String = "$AGENT$uuid"

    fun encodeNpc(id: NpcId): String = "$NPC${id.value}"

    fun encodeMount(id: MountId): String = "$MOUNT${id.value}"

    fun parseAgent(raw: String): AgentId? = parseTyped(raw, AGENT)?.let(::AgentId)

    fun parseNpc(raw: String): NpcId? = parseTyped(raw, NPC)?.let(::NpcId)

    fun parseMount(raw: String): MountId? = parseTyped(raw, MOUNT)?.let(::MountId)

    /**
     * Parse a wire id that may be either [AgentId] or [NpcId] — the input to
     * the consolidated `attack` tool. Returns `null` for bare UUIDs, unknown
     * prefixes, or malformed UUIDs. Routes through [parseAgent] / [parseNpc]
     * so adding a third kind later (e.g. `humanoid:`, `boss:`) is a single
     * extra branch with no risk of prefix-overlap ambiguity.
     */
    fun parseAttackTarget(raw: String): AttackTarget? =
        parseAgent(raw)?.let(AttackTarget::Agent)
            ?: parseNpc(raw)?.let(AttackTarget::Npc)
            ?: parseMount(raw)?.let(AttackTarget::Mount)

    private fun parseTyped(raw: String, prefix: String): UUID? {
        if (!raw.startsWith(prefix)) return null
        return runCatching { UUID.fromString(raw.removePrefix(prefix)) }.getOrNull()
    }
}

/** Discriminator returned by [PrefixedIds.parseAttackTarget] — the consolidated `attack` tool dispatches on this. */
internal sealed interface AttackTarget {
    data class Agent(val id: AgentId) : AttackTarget
    data class Npc(val id: NpcId) : AttackTarget
    data class Mount(val id: MountId) : AttackTarget
}
