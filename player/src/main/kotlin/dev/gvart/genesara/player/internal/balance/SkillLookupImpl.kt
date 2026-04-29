package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import org.springframework.stereotype.Component

@Component
internal class SkillLookupImpl(
    private val props: SkillDefinitionProperties,
) : SkillLookup {

    private val byId: Map<SkillId, Skill> = props.catalog.entries.associate { (key, properties) ->
        val id = SkillId(key)
        id to properties.toSkill(id)
    }

    override fun byId(id: SkillId): Skill? = byId[id]

    override fun all(): List<Skill> = byId.values.toList()

    private fun SkillProperties.toSkill(id: SkillId): Skill = Skill(
        id = id,
        displayName = displayName,
        description = description,
        category = category,
    )
}
