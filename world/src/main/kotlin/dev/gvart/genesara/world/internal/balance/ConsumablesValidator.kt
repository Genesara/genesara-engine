package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Cross-validates `items.yaml` consumable entries at startup. The YAML binder catches
 * an unknown `gauge` (it's a Kotlin enum), but `amount` is a free-form Int — a typo
 * like `amount: 0` or `amount: -10` would ship a no-op (or worse, a covert poison).
 *
 * Mirrors [GatherablesValidator] in shape: fail fast with a clear error message
 * naming every offending item.
 */
@Component
internal class ConsumablesValidator(
    private val items: ItemLookup,
) {

    @PostConstruct
    fun validate() {
        val problems = items.all().mapNotNull { item ->
            val effect = item.consumable ?: return@mapNotNull null
            if (effect.amount <= 0) item.id.value to effect.amount else null
        }
        require(problems.isEmpty()) {
            buildString {
                append("Item catalog has consumables with non-positive amounts:\n")
                problems.forEach { (id, amount) ->
                    append("  $id → amount=$amount (must be > 0)\n")
                }
                append("Negative-amount poison effects are reserved for a future slice.")
            }
        }
    }
}
