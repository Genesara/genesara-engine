package dev.gvart.genesara.world

import dev.gvart.genesara.player.PerkEffect

@JvmInline
value class EquipmentSetId(val value: String) {
    init {
        require(value.isNotBlank()) { "EquipmentSetId must not be blank" }
        // The dispatcher synthesizes "set:<id>@<tier>:<index>" PerkIds for cooldown keying;
        // a set id containing ':' or '@' would deform the key and collide ambiguously.
        require(':' !in value && '@' !in value) {
            "EquipmentSetId '$value' must not contain ':' or '@' (reserved for synthetic perk-id keying)"
        }
    }

    override fun toString(): String = value
}

/**
 * A named group of equipment items granting threshold bonuses when an agent
 * has N pieces equipped. Pieces are declared in [pieces]; items stay
 * agnostic of which sets they belong to (an item can appear in multiple
 * sets). See ADR-0002.
 */
data class EquipmentSet(
    val id: EquipmentSetId,
    val pieces: Set<ItemId>,
    /** Tier count (must be ≥1) → bonuses granted while the agent has that many pieces equipped. */
    val thresholds: Map<Int, EquipmentSetThreshold>,
) {
    init {
        require(pieces.isNotEmpty()) { "$id: pieces must not be empty" }
        require(thresholds.isNotEmpty()) { "$id: thresholds must not be empty" }
        thresholds.keys.forEach { tier ->
            require(tier in 1..pieces.size) {
                "$id: threshold tier $tier must be in 1..${pieces.size} (pieces in set)"
            }
        }
    }

    /**
     * Bonuses granted by every threshold whose tier ≤ [equippedCount]. Tiers
     * stack additively — wearing 4 IRON pieces grants the 2-piece bonus AND
     * the 4-piece bonus (matches the WoW/Diablo convention).
     */
    fun activeBonuses(equippedCount: Int): List<EquippedBonus> =
        thresholds.entries
            .filter { (tier, _) -> tier <= equippedCount }
            .flatMap { it.value.bonuses }

    /**
     * Triggered passives armed by every threshold whose tier ≤ [equippedCount].
     * Returned as `(tier, indexInTier, effect)` triples so the dispatcher can
     * synthesize a stable cooldown id per active set-bonus trigger.
     */
    fun activeTriggeredPassives(equippedCount: Int): List<ActiveTriggeredPassive> =
        thresholds.entries
            .filter { (tier, _) -> tier <= equippedCount }
            .sortedBy { it.key }
            .flatMap { (tier, threshold) ->
                threshold.triggeredPassives.mapIndexed { idx, effect ->
                    ActiveTriggeredPassive(tier = tier, indexInTier = idx, effect = effect)
                }
            }

    data class ActiveTriggeredPassive(
        val tier: Int,
        val indexInTier: Int,
        val effect: PerkEffect.TriggeredPassive,
    )
}

data class EquipmentSetThreshold(
    val bonuses: List<EquippedBonus>,
    val triggeredPassives: List<PerkEffect.TriggeredPassive> = emptyList(),
) {
    init {
        require(bonuses.isNotEmpty() || triggeredPassives.isNotEmpty()) {
            "EquipmentSetThreshold must declare at least one bonus or triggered passive"
        }
    }
}
