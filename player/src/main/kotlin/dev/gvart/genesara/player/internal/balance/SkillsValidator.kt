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
    private val props: SkillDefinitionProperties,
) {

    @PostConstruct
    fun validate() {
        val all = lookup.all()
        require(all.isNotEmpty()) {
            "Skill catalog is empty — every gather would silently no-op. Check that " +
                "player-definition/skills/*.yaml is on the classpath and parsed."
        }
        val problems = all.flatMap { skill ->
            buildList {
                if (skill.displayName.isBlank()) add(skill.id.value to "missing display-name")
                if (skill.description.isBlank()) add(skill.id.value to "missing description")
                skill.levelEffect?.let { effect ->
                    if (effect.perLevelPct <= 0.0) {
                        add(skill.id.value to "level-effect.per-level-pct must be > 0 (got ${effect.perLevelPct})")
                    }
                }
            }
        } + props.catalog.flatMap { (key, properties) ->
            properties.levelEffect?.let { raw ->
                buildList {
                    if (raw.type == null) add(key to "level-effect.type missing")
                    if (raw.perLevelPct == null) {
                        add(key to "level-effect.per-level-pct missing")
                    } else if (raw.perLevelPct <= 0.0) {
                        add(key to "level-effect.per-level-pct must be > 0 (got ${raw.perLevelPct})")
                    }
                }
            }.orEmpty()
        }
        require(problems.isEmpty()) {
            buildString {
                append("Skill catalog failed validation:\n")
                problems.forEach { (id, problem) -> appendLine("  $id → $problem") }
            }
        }
    }
}
