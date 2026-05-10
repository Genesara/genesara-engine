package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * `:world`-side companion to `:player`'s ClassValidator. The class catalog
 * stores [DamageType] and [ActionCategory] keys as raw strings because the
 * enums live in `:world` and `:player` cannot import them. This validator
 * decodes every string and fails startup loudly when a misspelling slips in,
 * so runtime callers (`AttackReducer`, the level-10 scoring algorithm) can
 * read the maps without a per-lookup `runCatching`.
 */
@Component
internal class ClassCatalogConsistencyValidator(
    private val classes: ClassLookup,
) {

    @PostConstruct
    fun validate() {
        val damageNames = DamageType.entries.map { it.name }.toSet()
        val axisNames = ActionCategory.entries.map { it.name }.toSet()
        val problems = mutableListOf<String>()

        classes.all().forEach { def ->
            def.damageMultipliers.keys
                .filterNot { it in damageNames }
                .forEach { problems += "${def.id}: damage-multipliers.$it does not match any DamageType (known: ${damageNames.sorted()})" }
            def.behaviorFingerprint.keys
                .filterNot { it in axisNames }
                .forEach { problems += "${def.id}: behavior-fingerprint.$it does not match any ActionCategory (known: ${axisNames.sorted()})" }
        }

        require(problems.isEmpty()) {
            buildString {
                append("Class catalog cross-module validation failed:\n")
                problems.forEach { appendLine("  - $it") }
            }
        }
    }
}
