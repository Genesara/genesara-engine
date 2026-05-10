package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Startup sanity check on `player-definition/classes.yaml`. Catches the
 * mistakes that would otherwise surface as confused agent UX or silent
 * mis-scoring at the level-10 event:
 *
 *  - missing class entry (every [AgentClass] must have YAML)
 *  - blank display-name / description
 *  - non-positive sight-range
 *  - skill ids in primary / neutral / forbidden lists that don't decode
 *  - non-positive damage multipliers
 *  - negative behavior-fingerprint weights
 *
 * Damage-type and action-category string keys are validated by a sibling
 * validator in `:world` (those enums live there and `:player` cannot import).
 */
@Component
internal class ClassValidator(
    private val props: ClassDefinitionProperties,
    private val skills: SkillLookup,
) {

    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()
        val skillIds: Set<SkillId> = skills.all().map { it.id }.toSet()

        AgentClass.entries.forEach { id ->
            val entry = props.classes[id]
            if (entry == null) {
                problems += "$id: no entry in player-definition/classes.yaml"
                return@forEach
            }
            entry.collectProblems(id, skillIds, problems)
        }

        require(problems.isEmpty()) {
            buildString {
                append("Class catalog failed validation:\n")
                problems.forEach { appendLine("  - $it") }
            }
        }
    }

    private fun ClassProperties.collectProblems(
        id: AgentClass,
        skillIds: Set<SkillId>,
        out: MutableList<String>,
    ) {
        if (displayName.isBlank()) out += "$id: missing display-name"
        if (description.isBlank()) out += "$id: missing description"
        if (sightRange <= 0) out += "$id: sight-range must be > 0 (got $sightRange)"

        validateSkillRefs(id, "primary-skills", primarySkills, skillIds, out)
        validateSkillRefs(id, "neutral-skills", neutralSkills, skillIds, out)
        validateSkillRefs(id, "forbidden-combat-skills", forbiddenCombatSkills, skillIds, out)

        validateNoDuplicates(id, "primary-skills", primarySkills, out)
        validateNoDuplicates(id, "neutral-skills", neutralSkills, out)
        validateNoDuplicates(id, "forbidden-combat-skills", forbiddenCombatSkills, out)

        val overlap = primarySkills.toSet() intersect neutralSkills.toSet()
        if (overlap.isNotEmpty()) {
            out += "$id: skills appear in both primary and neutral lists: ${overlap.sorted()}"
        }

        damageMultipliers.forEach { (type, mult) ->
            if (mult <= 0.0) out += "$id: damage-multipliers.$type must be > 0 (got $mult)"
        }
        behaviorFingerprint.forEach { (axis, weight) ->
            if (weight < 0.0) out += "$id: behavior-fingerprint.$axis must be >= 0 (got $weight)"
        }
    }

    private fun validateSkillRefs(
        id: AgentClass,
        field: String,
        refs: List<String>,
        skillIds: Set<SkillId>,
        out: MutableList<String>,
    ) {
        refs.forEach { raw ->
            if (SkillId(raw) !in skillIds) {
                out += "$id: $field references unknown skill id '$raw'"
            }
        }
    }

    private fun validateNoDuplicates(
        id: AgentClass,
        field: String,
        refs: List<String>,
        out: MutableList<String>,
    ) {
        val duplicates = refs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            out += "$id: $field has duplicate entries: ${duplicates.sorted()}"
        }
    }
}
