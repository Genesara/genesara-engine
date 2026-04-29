package dev.gvart.genesara.player

interface SkillLookup {
    fun byId(id: SkillId): Skill?
    fun all(): List<Skill>
}
