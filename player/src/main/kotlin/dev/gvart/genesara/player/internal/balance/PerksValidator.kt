package dev.gvart.genesara.player.internal.balance

/**
 * Catalog-shape sanity check, called from [PerkLookupImpl]'s init before any `!!`
 * translation runs. Aggregates every problem so a malformed catalog surfaces as a
 * single readable message rather than a cascade of NPEs.
 */
internal object PerksValidator {

    private val LEGAL_MILESTONES = setOf(50, 100, 150)
    private const val PERKS_PER_MILESTONE = 2

    fun collectProblems(props: SkillDefinitionProperties): List<String> {
        val problems = mutableListOf<String>()
        val perkIdLocations = mutableMapOf<String, MutableList<String>>()
        val abilityIdLocations = mutableMapOf<String, MutableList<String>>()

        for ((skillKey, skillProps) in props.catalog) {
            if (skillProps.milestones.isEmpty()) continue
            for ((rawLevel, perks) in skillProps.milestones) {
                val location = "$skillKey @ milestone $rawLevel"
                val level = rawLevel.toIntOrNull()
                if (level == null) {
                    problems += "$location: milestone key '$rawLevel' is not an integer"
                    continue
                }
                if (level !in LEGAL_MILESTONES) {
                    problems += "$location: milestone level must be one of $LEGAL_MILESTONES"
                }
                if (perks.size != PERKS_PER_MILESTONE) {
                    problems += "$location: expected exactly $PERKS_PER_MILESTONE perks (1-of-2 fork), got ${perks.size}"
                }
                perks.forEachIndexed { idx, perk ->
                    validatePerk(perk, "$location[$idx]", problems)
                    if (perk.id.isNotBlank()) {
                        perkIdLocations.getOrPut(perk.id) { mutableListOf() } += "$skillKey/$rawLevel#$idx"
                    }
                    val abilityId = perk.effect.takeIf { it.type == PerkEffectType.ACTIVE_ABILITY }?.abilityId
                    if (!abilityId.isNullOrBlank()) {
                        abilityIdLocations.getOrPut(abilityId) { mutableListOf() } += "$skillKey/$rawLevel#$idx"
                    }
                }
            }
        }

        perkIdLocations
            .filterValues { it.size > 1 }
            .forEach { (id, locations) ->
                problems += "perk id '$id' is declared in multiple places: ${locations.joinToString(", ")}"
            }
        abilityIdLocations
            .filterValues { it.size > 1 }
            .forEach { (id, locations) ->
                problems += "ability id '$id' is declared in multiple perks: ${locations.joinToString(", ")}"
            }

        return problems
    }

    private fun validatePerk(perk: PerkProperties, location: String, problems: MutableList<String>) {
        if (perk.id.isBlank()) problems += "$location: perk id is blank"
        // The equipment-set dispatcher synthesizes PerkIds prefixed `"set:"` for cooldown
        // keying (world/internal/perks/TriggeredPassiveDispatcher). A real catalog perk
        // sharing that prefix would collide in PerkCooldownStore.
        if (perk.id.startsWith("set:")) {
            problems += "$location: perk id '${perk.id}' must not start with 'set:' (reserved prefix for equipment-set triggers)"
        }
        if (perk.displayName.isBlank()) problems += "$location: missing display-name"
        if (perk.description.isBlank()) problems += "$location: missing description"
        validateEffect(perk.effect, location, problems)
    }

    private fun validateEffect(
        effect: PerkEffectProperties,
        location: String,
        problems: MutableList<String>,
    ) {
        when (effect.type) {
            null -> problems += "$location: effect.type is required"
            PerkEffectType.ACTIVE_ABILITY -> {
                requireField(effect.abilityId, "$location.effect.ability-id", problems)
                requireField(effect.costResource, "$location.effect.cost-resource", problems)
                requireField(effect.costAmount, "$location.effect.cost-amount", problems)
                requireField(effect.abilityTarget, "$location.effect.ability-target", problems)
                requireField(effect.cooldownTicks, "$location.effect.cooldown-ticks", problems)
                requireField(effect.abilityEffectKind, "$location.effect.ability-effect-kind", problems)
                if (effect.costAmount != null && effect.costAmount < 0) {
                    problems += "$location.effect.cost-amount: must be >= 0"
                }
                if (effect.cooldownTicks != null && effect.cooldownTicks <= 0) {
                    // Strictly positive: the spec promises "ability goes on cooldown" — a
                    // zero CD turns the active into a free-spam; let's catch that at load.
                    problems += "$location.effect.cooldown-ticks: must be > 0"
                }
            }
            PerkEffectType.PASSIVE_AURA -> {
                requireField(effect.auraTarget, "$location.effect.aura-target", problems)
                requireField(effect.auraMagnitude, "$location.effect.aura-magnitude", problems)
                if (effect.auraMagnitude != null && effect.auraMagnitude <= 0) {
                    problems += "$location.effect.aura-magnitude: must be > 0"
                }
            }
            PerkEffectType.TRIGGERED_PASSIVE -> {
                requireField(effect.trigger, "$location.effect.trigger", problems)
                requireField(effect.effectKind, "$location.effect.effect-kind", problems)
                requireField(effect.internalCooldownTicks, "$location.effect.internal-cooldown-ticks", problems)
                if (effect.internalCooldownTicks != null && effect.internalCooldownTicks < 0) {
                    problems += "$location.effect.internal-cooldown-ticks: must be >= 0"
                }
            }
            PerkEffectType.MODIFIER -> {
                requireField(effect.modifierTarget, "$location.effect.modifier-target", problems)
                requireField(effect.multiplier, "$location.effect.multiplier", problems)
            }
        }
    }

    private fun requireField(value: Any?, label: String, problems: MutableList<String>) {
        if (value == null) problems += "$label is required"
        if (value is String && value.isBlank()) problems += "$label is blank"
    }
}
