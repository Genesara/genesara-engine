package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.internal.crafting.RarityRoller
import java.util.UUID
import kotlin.random.Random

/** Default catalog used by tests / dispatch calls that don't exercise NPCs. */
internal val NoOpNpcCatalogDefault: NpcCatalog = object : NpcCatalog {
    override fun byType(type: NpcType): NpcDef? = null
    override fun all(): Collection<NpcDef> = emptyList()
    override fun byBiome(biome: Biome): List<NpcDef> = emptyList()
}

private object EmptyLootTables : LootTableCatalog {
    override fun byMob(mob: NpcType): List<LootEntry> = emptyList()
}

private object EmptyItems : ItemLookup {
    override fun byId(id: ItemId): Item? = null
    override fun all(): List<Item> = emptyList()
}

private object SilentGroundItems : GroundItemStore {
    override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = Unit
    override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
    override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
}

/**
 * Default [LootRoll] used by the dispatch fallback. Production binds the real
 * [LootRoll] @Component; non-NPC reducer tests inherit this and observe no
 * loot side effects.
 */
internal val NoOpLootRoll: LootRoll = LootRoll(
    lootTables = EmptyLootTables,
    items = EmptyItems,
    groundItems = SilentGroundItems,
    rarityRoller = RarityRoller(Random.Default),
)
