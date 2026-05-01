package dev.gvart.genesara.world.internal.inventory

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.internal.balance.BalanceLookup

internal fun AgentInventory.totalGrams(items: ItemLookup): Int =
    stacks.entries.sumOf { (id, qty) -> (items.byId(id)?.weightPerUnit ?: 0) * qty }

// TODO(carry-weight): equippedGrams only sees slotted instances. Spec §8 says stowed +
// equipped count; once equip / craft / loot slices land, walk listByAgent(...) and add
// the unequipped tail. Today no flow produces unequipped rows, so the gap isn't reachable.
internal fun equippedGrams(equipped: Map<EquipSlot, EquipmentInstance>, items: ItemLookup): Int =
    equipped.values.sumOf { items.byId(it.itemId)?.weightPerUnit ?: 0 }

internal fun Raise<WorldRejection>.enforceCarryCap(
    agent: AgentId,
    strength: Int,
    currentGrams: Int,
    additionalGrams: Int,
    balance: BalanceLookup,
) {
    // All math widened to Long so a permissive test stub or a misconfigured catalog
    // (heavy item × big yield) can't silently wrap to a negative Int and bypass the cap;
    // the rejection's Int fields clamp at Int.MAX_VALUE so the agent gets a meaningful
    // gap report even in the over-Int case.
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
