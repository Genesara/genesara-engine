package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.SkillId
import org.springframework.stereotype.Component

@Component
internal class PerkLookupImpl(
    private val props: SkillDefinitionProperties,
) : PerkLookup {

    private val byId: Map<PerkId, Perk>
    private val choicesBySkill: Map<SkillId, List<PerkChoice>>

    init {
        val problems = PerksValidator.collectProblems(props)
        require(problems.isEmpty()) {
            buildString {
                append("Perk catalog failed validation:\n")
                problems.forEach { appendLine("  - $it") }
            }
        }
        val flatPerks = mutableListOf<Perk>()
        val perSkill = mutableMapOf<SkillId, MutableList<PerkChoice>>()
        for ((skillKey, skillProps) in props.catalog) {
            val skill = SkillId(skillKey)
            val sortedMilestones = skillProps.milestones.entries
                .map { (levelKey, perks) -> levelKey.toInt() to perks }
                .sortedBy { it.first }
            for ((level, perks) in sortedMilestones) {
                val options = perks.map { it.toPerk(skill, level) }
                flatPerks += options
                perSkill.getOrPut(skill) { mutableListOf() } += PerkChoice(skill, level, options)
            }
        }
        byId = flatPerks.associateBy { it.id }
        choicesBySkill = perSkill.mapValues { (_, list) -> list.toList() }
    }

    override fun byId(id: PerkId): Perk? = byId[id]

    override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? =
        choicesBySkill[skill]?.firstOrNull { it.milestoneLevel == milestoneLevel }

    override fun choicesFor(skill: SkillId): List<PerkChoice> = choicesBySkill[skill].orEmpty()

    override fun all(): List<Perk> = byId.values.toList()

    private fun PerkProperties.toPerk(skill: SkillId, milestoneLevel: Int): Perk = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = milestoneLevel,
        displayName = displayName,
        description = description,
        effect = effect.toEffect(),
    )

    private fun PerkEffectProperties.toEffect(): PerkEffect = when (type) {
        PerkEffectType.ACTIVE_ABILITY -> PerkEffect.ActiveAbility(
            abilityId = abilityId!!,
            costResource = costResource!!,
            costAmount = costAmount!!,
            target = abilityTarget!!,
            cooldownTicks = cooldownTicks!!,
        )
        PerkEffectType.PASSIVE_AURA -> PerkEffect.PassiveAura(
            target = auraTarget!!,
            magnitude = auraMagnitude!!,
        )
        PerkEffectType.TRIGGERED_PASSIVE -> PerkEffect.TriggeredPassive(
            trigger = trigger!!,
            effectKind = effectKind!!,
            params = params,
            internalCooldownTicks = internalCooldownTicks!!,
        )
        PerkEffectType.MODIFIER -> PerkEffect.Modifier(
            target = modifierTarget!!,
            multiplier = multiplier!!,
        )
        null -> error("perk effect type is null — PerksValidator should have rejected this at startup")
    }
}
