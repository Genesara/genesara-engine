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

        validateEvolutionLinks(problems)

        require(problems.isEmpty()) {
            buildString {
                append("Class catalog failed validation:\n")
                problems.forEach { appendLine("  - $it") }
            }
        }
    }

    /**
     * Cross-check the parent ↔ evolutions back-link for the L50 system (#34):
     *  - every `evolutions` entry must point at a class with `parent-class` set to the parent,
     *  - every `parent-class` ref must point at a base class (no chained evolutions in v1),
     *  - the parent's `evolutions` list must contain this class,
     *  - the parent must not list itself as an evolution.
     */
    private fun validateEvolutionLinks(out: MutableList<String>) {
        AgentClass.entries.forEach { id ->
            val entry = props.classes[id] ?: return@forEach

            entry.parentClass?.let { parent ->
                val parentEntry = props.classes[parent]
                if (parentEntry == null) {
                    out += "$id: parent-class $parent has no entry"
                    return@let
                }
                if (parentEntry.parentClass != null) {
                    out += "$id: parent-class $parent is itself an evolution (chained evolutions are out of scope in v1)"
                }
                if (id !in parentEntry.evolutions) {
                    out += "$id: parent-class $parent does not list $id in its evolutions"
                }
            }

            entry.evolutions.forEach { evo ->
                if (evo == id) {
                    out += "$id: lists itself as an evolution"
                    return@forEach
                }
                val evoEntry = props.classes[evo]
                if (evoEntry == null) {
                    out += "$id: evolutions reference $evo which has no entry"
                    return@forEach
                }
                if (evoEntry.parentClass != id) {
                    out += "$id: lists $evo as an evolution but $evo's parent-class is ${evoEntry.parentClass ?: "null (base class)"}"
                }
                // Spec mechanics-reference §4.1: hard restrictions don't auto-inherit
                // from the parent — the YAML must restate them. The validator catches
                // a missing parent forbid as a startup failure rather than letting it
                // slip into combat (e.g. a Researcher evolution that drops the FIREARMS
                // ban would silently let scholars wield rifles).
                val parentForbids = entry.forbiddenCombatSkills.toSet()
                val evoForbids = evoEntry.forbiddenCombatSkills.toSet()
                val missing = parentForbids - evoForbids
                if (missing.isNotEmpty()) {
                    out += "$evo: missing parent-class $id's forbidden-combat-skills: ${missing.sorted()}"
                }
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
