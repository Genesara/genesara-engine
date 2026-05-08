package dev.gvart.genesara.player.internal.scaling

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerk
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentPerksSnapshot
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.LevelEffect
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class LevelScalingAggregatorImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val sword = SkillId("SWORD")
    private val club = SkillId("CLUB")
    private val swordDoubledEdge = PerkId("SWORD_DOUBLED_EDGE")

    @Test
    fun `returns 0 when agent has no slotted skills`() {
        val agg = aggregator(skills = catalogWithSwordScaling(), perks = emptyList(), slottedSkills = emptyMap(), chosenPerks = emptyList())
        assertEquals(0.0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `returns 0 when slotted skill has no level-effect`() {
        val agg = aggregator(
            skills = listOf(plainSwordSkill()),
            perks = emptyList(),
            slottedSkills = mapOf(sword to 100),
            chosenPerks = emptyList(),
        )
        assertEquals(0.0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `returns 0 when slotted skill scales a different effect`() {
        val agg = aggregator(
            skills = catalogWithSwordScaling(),
            perks = emptyList(),
            slottedSkills = mapOf(sword to 50),
            chosenPerks = emptyList(),
        )
        assertEquals(0.0, agg.bonusFor(agent, ScalingEffect.PIERCE_DAMAGE_BONUS))
    }

    @Test
    fun `scales linearly with level — L1 to L150 unmodified`() {
        listOf(1 to 0.005, 50 to 0.25, 100 to 0.50, 150 to 0.75).forEach { (level, expected) ->
            val agg = aggregator(
                skills = catalogWithSwordScaling(),
                perks = emptyList(),
                slottedSkills = mapOf(sword to level),
                chosenPerks = emptyList(),
            )
            assertEquals(expected, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
        }
    }

    @Test
    fun `scales beyond L150 — uncapped per spec section 3`() {
        val agg = aggregator(
            skills = catalogWithSwordScaling(),
            perks = emptyList(),
            slottedSkills = mapOf(sword to 200),
            chosenPerks = emptyList(),
        )
        assertEquals(1.0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    @Test
    fun `Modifier perk on the same skill multiplies that skill's contribution`() {
        val agg = aggregator(
            skills = catalogWithSwordScaling(),
            perks = listOf(doubledEdgePerk()),
            slottedSkills = mapOf(sword to 150),
            chosenPerks = listOf(swordDoubledEdge),
        )
        assertEquals(1.5, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    @Test
    fun `Modifier on a different skill does not affect this skill's contribution`() {
        val clubModifier = perk(
            id = "CLUB_DOUBLED_BLOW",
            skill = club,
            milestone = 150,
            effect = PerkEffect.Modifier(target = ScalingEffect.SLASH_DAMAGE_BONUS, multiplier = 2.0),
        )
        val agg = aggregator(
            skills = listOf(swordWithSlashScaling(), plainClubSkill()),
            perks = listOf(clubModifier),
            slottedSkills = mapOf(sword to 150, club to 150),
            chosenPerks = listOf(PerkId("CLUB_DOUBLED_BLOW")),
        )
        assertEquals(0.75, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    @Test
    fun `multiple slotted skills sum their contributions for the same effect`() {
        val agg = aggregator(
            skills = listOf(swordWithSlashScaling(), clubWithSlashScaling()),
            perks = emptyList(),
            slottedSkills = mapOf(sword to 100, club to 50),
            chosenPerks = emptyList(),
        )
        assertEquals(0.50 + 0.25, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    @Test
    fun `two Modifier perks on the same skill targeting the same effect compose multiplicatively`() {
        val secondModifier = perk(
            id = "SWORD_HONED_EDGE",
            skill = sword,
            milestone = 100,
            effect = PerkEffect.Modifier(target = ScalingEffect.SLASH_DAMAGE_BONUS, multiplier = 1.5),
        )
        val agg = aggregator(
            skills = catalogWithSwordScaling(),
            perks = listOf(doubledEdgePerk(), secondModifier),
            slottedSkills = mapOf(sword to 100),
            chosenPerks = listOf(swordDoubledEdge, PerkId("SWORD_HONED_EDGE")),
        )
        // base = 100 × 0.005 = 0.5; modifiers compose: 0.5 × 2.0 × 1.5 = 1.5
        assertEquals(1.5, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    @Test
    fun `Modifier on the same skill targeting a different effect does not apply`() {
        val blockModifier = perk(
            id = "SWORD_GUARD",
            skill = sword,
            milestone = 100,
            effect = PerkEffect.Modifier(target = ScalingEffect.BLOCK_CHANCE, multiplier = 3.0),
        )
        val agg = aggregator(
            skills = catalogWithSwordScaling(),
            perks = listOf(blockModifier),
            slottedSkills = mapOf(sword to 100),
            chosenPerks = listOf(PerkId("SWORD_GUARD")),
        )
        // SWORD scales SLASH; the BLOCK_CHANCE modifier must not multiply it.
        assertEquals(0.50, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    @Test
    fun `non-Modifier perks like PassiveAura are ignored by the scaling aggregator`() {
        val auraPerk = perk(
            id = "SWORD_SHARPEN_EDGE",
            skill = sword,
            milestone = 50,
            effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 5),
        )
        val agg = aggregator(
            skills = catalogWithSwordScaling(),
            perks = listOf(auraPerk),
            slottedSkills = mapOf(sword to 100),
            chosenPerks = listOf(PerkId("SWORD_SHARPEN_EDGE")),
        )
        assertEquals(0.50, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS), absoluteTolerance = 1e-9)
    }

    private fun aggregator(
        skills: List<Skill>,
        perks: List<Perk>,
        slottedSkills: Map<SkillId, Int>,
        chosenPerks: List<PerkId>,
    ) = LevelScalingAggregatorImpl(
        skills = StubSkillLookup(skills),
        perks = StubPerkLookup(perks),
        skillsRegistry = StubSkillsRegistry(slottedSkills),
        perksRegistry = StubPerksRegistry(chosenPerks),
    )

    private fun catalogWithSwordScaling(): List<Skill> = listOf(swordWithSlashScaling())

    private fun swordWithSlashScaling() = Skill(
        id = sword,
        displayName = "Sword",
        description = "blade",
        category = SkillCategory.COMBAT,
        levelEffect = LevelEffect(ScalingEffect.SLASH_DAMAGE_BONUS, perLevelPct = 0.005),
    )

    private fun clubWithSlashScaling() = Skill(
        id = club,
        displayName = "Club",
        description = "blunt",
        category = SkillCategory.COMBAT,
        levelEffect = LevelEffect(ScalingEffect.SLASH_DAMAGE_BONUS, perLevelPct = 0.005),
    )

    private fun plainSwordSkill() = Skill(sword, "Sword", "blade", SkillCategory.COMBAT, levelEffect = null)

    private fun plainClubSkill() = Skill(club, "Club", "blunt", SkillCategory.COMBAT, levelEffect = null)

    private fun doubledEdgePerk() = perk(
        id = swordDoubledEdge.value,
        skill = sword,
        milestone = 150,
        effect = PerkEffect.Modifier(target = ScalingEffect.SLASH_DAMAGE_BONUS, multiplier = 2.0),
    )

    private fun perk(id: String, skill: SkillId, milestone: Int, effect: PerkEffect): Perk = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = milestone,
        displayName = id,
        description = id,
        effect = effect,
    )

    private class StubSkillLookup(private val skills: List<Skill>) : SkillLookup {
        private val byId = skills.associateBy { it.id }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = skills
    }

    private class StubPerkLookup(private val perks: List<Perk>) : PerkLookup {
        private val byId = perks.associateBy { it.id }
        override fun byId(id: PerkId): Perk? = byId[id]
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? = null
        override fun choicesFor(skill: SkillId): List<PerkChoice> = emptyList()
        override fun all(): List<Perk> = perks
    }

    private inner class StubSkillsRegistry(private val slotted: Map<SkillId, Int>) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = slotted.entries.mapIndexed { idx, (skill, level) ->
                skill to AgentSkillState(
                    skill = skill,
                    xp = 0,
                    level = level,
                    slotIndex = idx,
                    recommendCount = 0,
                )
            }.toMap(),
            slotCount = slotted.size,
            slotsFilled = slotted.size,
        )

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult =
            AddXpResult.Unslotted

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private inner class StubPerksRegistry(private val chosen: List<PerkId>) : AgentPerksRegistry {
        override fun snapshot(agent: AgentId): AgentPerksSnapshot = AgentPerksSnapshot(
            perks = chosen.map { id ->
                AgentPerk(
                    skill = SkillId("ANY"),
                    milestoneLevel = 0,
                    perkId = id,
                    chosenAtTick = 0,
                )
            },
        )

        override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult =
            RecordPerkResult.Recorded
    }
}
