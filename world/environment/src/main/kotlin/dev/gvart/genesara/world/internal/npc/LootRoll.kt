package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.balance.RarityRoller
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random
import org.springframework.stereotype.Component

/**
 * Per-kill loot resolver. Each entry on [LootTableCatalog.byMob] rolls
 * independently against `dropChance`; a successful entry yields a uniform
 * `quantityMin..quantityMax` count for stackables, or a single equipment
 * instance whose [Rarity] is rolled via [RarityRoller] using the killer's
 * combat-skill level + LUCK (Q9c, Q11d).
 *
 * Drops are deposited inline via [GroundItemStore.deposit] so consumers (the
 * existing pickup verb, vision filter) see them on the same tick. The caller
 * is responsible for emitting [EnvironmentEvent.NpcDied] + per-drop
 * [EconomyEvent.ItemDroppedOnGround] events using the returned list.
 */
@Component
class LootRoll(
    private val lootTables: LootTableCatalog,
    private val items: ItemLookup,
    private val groundItems: GroundItemStore,
    private val rarityRoller: RarityRoller,
) {
    fun rollAndDeposit(
        npcType: NpcType,
        node: NodeId,
        killerCombatSkillLevel: Int,
        killerLuck: Int,
        huntingLootBonus: Double,
        tick: Long,
        rng: Random,
    ): List<DroppedItemView> {
        val entries = lootTables.byMob(npcType)
        if (entries.isEmpty()) return emptyList()

        val drops = mutableListOf<DroppedItemView>()
        for (entry in entries) {
            if (rng.nextDouble() >= entry.dropChance) continue
            val item = items.byId(entry.item) ?: continue
            val drop = when (item.category) {
                ItemCategory.RESOURCE -> {
                    val qty = stackableQuantity(entry, huntingLootBonus, rng)
                    DroppedItemView.Stackable(
                        dropId = UUID.randomUUID(),
                        item = entry.item,
                        quantity = qty,
                    )
                }
                ItemCategory.EQUIPMENT -> {
                    val rarity = rarityRoller.roll(killerCombatSkillLevel, killerLuck)
                    DroppedItemView.Equipment(
                        dropId = UUID.randomUUID(),
                        item = entry.item,
                        instanceId = UUID.randomUUID(),
                        rarity = rarity,
                        durabilityCurrent = item.maxDurability ?: 1,
                        durabilityMax = item.maxDurability ?: 1,
                        creatorAgentId = null,
                        createdAtTick = tick,
                    )
                }
                ItemCategory.KEY -> continue
            }
            groundItems.deposit(node, drop, tick)
            drops += drop
        }
        return drops
    }

    /**
     * Quantity for a stackable drop. Base is the uniform `[min, max]` roll; the
     * killer's HUNTING-derived [huntingLootBonus] adds `floor(bonus * (max-min))`
     * extra units on top, clamped to the entry's `max`. Bonus is clamped to
     * `[0.0, 1.0]` — at saturation the result is always `max`.
     */
    private fun stackableQuantity(entry: LootEntry, huntingLootBonus: Double, rng: Random): Int {
        val min = entry.quantityMin.coerceAtLeast(1)
        val max = entry.quantityMax.coerceAtLeast(min)
        if (max == min) return min
        val baseRoll = rng.nextInt(min, max + 1)
        val range = max - min
        val clampedBonus = huntingLootBonus.coerceIn(0.0, 1.0)
        val bonusShift = floor(clampedBonus * range).toInt()
        return (baseRoll + bonusShift).coerceAtMost(max)
    }
}
