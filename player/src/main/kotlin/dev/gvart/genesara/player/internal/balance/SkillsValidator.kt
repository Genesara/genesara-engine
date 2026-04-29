package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.SkillLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Startup sanity check on the skill catalog. Catches obvious YAML mistakes (blank
 * display name, missing description) so they fail fast instead of surfacing as
 * confused agent UX. Cross-validation against catalogs in other modules (e.g. items
 * referencing a skill id) lives in those modules' validators.
 */
@Component
internal class SkillsValidator(
    private val lookup: SkillLookup,
) {

    @PostConstruct
    fun validate() {
        val all = lookup.all()
        require(all.isNotEmpty()) {
            "Skill catalog is empty — every gather would silently no-op. Check that " +
                "player-definition/skills.yaml is on the classpath and parsed."
        }
        val problems = all.mapNotNull { skill ->
            when {
                skill.displayName.isBlank() -> skill.id.value to "missing display-name"
                skill.description.isBlank() -> skill.id.value to "missing description"
                else -> null
            }
        }
        require(problems.isEmpty()) {
            buildString {
                append("Skill catalog failed validation:\n")
                problems.forEach { (id, problem) -> appendLine("  $id → $problem") }
            }
        }
    }
}
