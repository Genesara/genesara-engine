package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.NodeClearedTimestampStore

/** Empty [NpcsStore] for tests that don't exercise NPC behavior. */
internal object NoOpNpcsStore : NpcsStore {
    override fun insert(npc: Npc) = error("NoOpNpcsStore: insert should not be called in this test")
    override fun findById(npcId: NpcId): Npc? = null
    override fun byNodes(nodeIds: Collection<NodeId>): List<Npc> = emptyList()
    override fun delete(npcId: NpcId): Boolean = false
    override fun countAtNode(nodeId: NodeId): Int = 0
    override fun update(npc: Npc) = error("NoOpNpcsStore: update should not be called in this test")
}

/** Empty [NpcCatalog] for tests that don't exercise NPC behavior. */
internal object NoOpNpcCatalog : NpcCatalog {
    override fun byType(type: NpcType): NpcDef? = null
    override fun all(): Collection<NpcDef> = emptyList()
    override fun byBiome(biome: Biome): List<NpcDef> = emptyList()
}

/** Default-zero [NodeClearedTimestampStore] — every node always spawn-eligible. */
internal object NoOpNodeClearedTimestampStore : NodeClearedTimestampStore {
    override fun lastClearedTick(nodeId: NodeId): Long = 0L
    override fun setLastClearedTick(nodeId: NodeId, tick: Long) = Unit
}
