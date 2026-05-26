package dev.gvart.genesara.world.environment

import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType

/**
 * Direct per-instance NPC manipulation for admin tooling, sitting outside the
 * tick-loop reducer path. Spawns and non-silent kills emit the same
 * [dev.gvart.genesara.world.events.EnvironmentEvent.NpcSpawned] /
 * [dev.gvart.genesara.world.events.EnvironmentEvent.NpcDied] events as the
 * gameplay path, so per-agent feeds stay consistent regardless of who
 * triggered the spawn.
 */
interface AdminNpcGateway {
    /** Inserts a fresh NPC at [nodeId] and emits `NpcSpawned`. */
    fun spawn(nodeId: NodeId, type: NpcType, hp: Int?, tick: Long): Npc

    /** Live NPCs currently positioned at [nodeId]. */
    fun listAtNode(nodeId: NodeId): List<Npc>

    /**
     * Mutates [npcId]'s HP and/or node. Returns the post-update [Npc].
     * `null` updates leave the field untouched; immutable fields
     * (`spawnNodeId`, `type`) cannot be changed.
     */
    fun update(npcId: NpcId, hp: Int?, nodeId: NodeId?, tick: Long): Npc

    /**
     * Removes [npcId]. When [silent] is false (default), rolls loot via the
     * existing path and emits `NpcDied` + per-drop `ItemDroppedOnGround`.
     * When [silent] is true, deletes the row and emits nothing — the audit
     * row is the only trace.
     */
    fun kill(npcId: NpcId, silent: Boolean, tick: Long): KillOutcome
}

data class KillOutcome(
    val npc: Npc,
    val drops: List<DroppedItemView>,
)

sealed class AdminNpcGatewayError(message: String) : RuntimeException(message) {
    class UnknownType(val type: NpcType) :
        AdminNpcGatewayError("Unknown NPC type: ${type.value}")
    class NotFound(val npcId: NpcId) :
        AdminNpcGatewayError("NPC not found: ${npcId.value}")
    class InvalidHp(val requested: Int, val hpMax: Int) :
        AdminNpcGatewayError("hp ($requested) must be in 1..$hpMax")
}
