package dev.gvart.genesara.world.internal.worldstate.views

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId

/**
 * Typed read access to the environment slice (NPCs in the active set).
 */
interface EnvironmentReadView {
    val npcs: Map<NpcId, Npc>

    fun npcsAt(node: NodeId): List<Npc> = npcs.values.filter { it.nodeId == node }
}
