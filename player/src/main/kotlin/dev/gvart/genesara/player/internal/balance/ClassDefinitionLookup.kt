package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.SkillId
import org.springframework.stereotype.Component

@Component
internal class ClassDefinitionLookup(
    private val props: ClassDefinitionProperties,
) : ClassLookup {

    private val byId: Map<AgentClass, ClassDefinition> =
        AgentClass.entries
            .mapNotNull { id -> props.classes[id]?.let { id to it.toDefinition(id) } }
            .toMap()

    override fun byId(classId: AgentClass): ClassDefinition? = byId[classId]

    override fun all(): List<ClassDefinition> =
        AgentClass.entries.mapNotNull { byId[it] }

    override fun baseClasses(): List<ClassDefinition> =
        AgentClass.entries.mapNotNull { byId[it] }.filter { it.parentClass == null }

    override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> =
        byId[parent]?.evolutions
            ?.mapNotNull { byId[it] }
            ?: emptyList()

    override fun sightRange(classId: AgentClass?): Int =
        classId?.let { byId[it]?.sightRange } ?: props.default.sightRange

    override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double {
        val def = classId?.let { byId[it] } ?: return 1.0
        return when (skill) {
            in def.primarySkills -> PRIMARY_SKILL_XP
            in def.neutralSkills -> NEUTRAL_SKILL_XP
            else -> OFF_BUILD_SKILL_XP
        }
    }

    override fun damageMultiplier(classId: AgentClass?, damageType: String): Double {
        val def = classId?.let { byId[it] } ?: return 1.0
        return def.damageMultipliers[damageType] ?: 1.0
    }

    override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean {
        val def = classId?.let { byId[it] } ?: return false
        return combatSkill in def.forbiddenCombatSkills
    }

    private fun ClassProperties.toDefinition(id: AgentClass): ClassDefinition = ClassDefinition(
        id = id,
        displayName = displayName,
        description = description,
        sightRange = sightRange,
        primarySkills = primarySkills.map(::SkillId).toSet(),
        neutralSkills = neutralSkills.map(::SkillId).toSet(),
        forbiddenCombatSkills = forbiddenCombatSkills.map(::SkillId).toSet(),
        damageMultipliers = damageMultipliers,
        behaviorFingerprint = behaviorFingerprint,
        parentClass = parentClass,
        evolutions = evolutions,
    )

    private companion object {
        const val PRIMARY_SKILL_XP = 1.5
        const val NEUTRAL_SKILL_XP = 1.0
        const val OFF_BUILD_SKILL_XP = 0.5
    }
}
