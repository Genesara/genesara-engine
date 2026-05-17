package dev.gvart.genesara.world.internal.inventory

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.internal.balance.BalanceLookup

fun AgentInventory.totalGrams(items: ItemLookup): Int =
    stacks.entries.sumOf { (id, qty) -> (items.byId(id)?.weightPerUnit ?: 0) * qty }

// TODO(carry-weight): also count unequipped ItemInstance.Equipment rows once a flow produces them.
fun equippedGrams(equipped: Map<EquipSlot, ItemInstance.Equipment>, items: ItemLookup): Int =
    equipped.values.sumOf { items.byId(it.itemId)?.weightPerUnit ?: 0 }

fun Raise<WorldRejection>.enforceCarryCap(
    agent: AgentId,
    strength: Int,
    currentGrams: Int,
    additionalGrams: Int,
    balance: BalanceLookup,
) {
    val capacityLong = strength.toLong() * balance.carryGramsPerStrengthPoint().toLong()
    val requestedLong = currentGrams.toLong() + additionalGrams.toLong()
    ensure(requestedLong <= capacityLong) {
        WorldRejection.OverEncumbered(
            agent = agent,
            requested = requestedLong.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            capacity = capacityLong.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}
