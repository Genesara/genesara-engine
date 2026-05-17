package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Boot-time check that every set piece resolves in [ItemLookup] AND has
 * `category: EQUIPMENT`. Set bonuses only make sense on items that can be
 * equipped — a typo or accidental RESOURCE inclusion silently dies otherwise.
 */
@Component
internal class EquipmentSetReferentialValidator(
    private val sets: EquipmentSetLookup,
    private val items: ItemLookup,
) {

    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()
        for (set in sets.all()) {
            for (piece in set.pieces) {
                val item = items.byId(piece)
                if (item == null) {
                    problems += "${set.id}: piece '${piece.value}' is not in the items catalog"
                } else if (item.category != ItemCategory.EQUIPMENT) {
                    problems += "${set.id}: piece '${piece.value}' is ${item.category} — must be EQUIPMENT"
                }
            }
        }
        require(problems.isEmpty()) {
            buildString {
                append("Equipment-set referential validation failed:\n")
                problems.forEach { append("  - $it\n") }
            }
        }
    }
}
