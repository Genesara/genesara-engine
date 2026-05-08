package dev.gvart.genesara.player

/** Catalog-level read surface — backed by `player-definition/skills.yaml` milestone blocks. */
interface PerkLookup {

    fun byId(id: PerkId): Perk?

    fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice?

    /** Forks for [skill], ordered by milestone level ascending. */
    fun choicesFor(skill: SkillId): List<PerkChoice>

    fun all(): List<Perk>
}
