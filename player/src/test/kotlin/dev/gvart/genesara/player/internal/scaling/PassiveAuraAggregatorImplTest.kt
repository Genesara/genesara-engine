package dev.gvart.genesara.player.internal.scaling

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerk
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentPerksSnapshot
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class PassiveAuraAggregatorImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val sword = SkillId("SWORD")
    private val bow = SkillId("BOW")

    private val sharpenEdge = perk("SWORD_SHARPEN_EDGE", sword, PerkEffect.PassiveAura(ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 5))
    private val precision = perk("SWORD_PRECISION", sword, PerkEffect.PassiveAura(ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 10))
    private val bowFlatPierce = perk("BOW_BARBED", bow, PerkEffect.PassiveAura(ScalingEffect.PIERCE_DAMAGE_BONUS, magnitude = 7))
    private val swordFlatBlock = perk("SWORD_GUARD", sword, PerkEffect.PassiveAura(ScalingEffect.BLOCK_CHANCE, magnitude = 3))
    private val swordModifier = perk(
        "SWORD_DOUBLED_EDGE", sword,
        PerkEffect.Modifier(target = ScalingEffect.SLASH_DAMAGE_BONUS, multiplier = 2.0),
    )

    @Test
    fun `returns 0 when agent has no chosen perks`() {
        val agg = aggregator(perks = listOf(sharpenEdge), slottedSkills = setOf(sword), chosenPerks = emptyList())
        assertEquals(0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `returns 0 when no skill is slotted`() {
        val agg = aggregator(perks = listOf(sharpenEdge), slottedSkills = emptySet(), chosenPerks = listOf(sharpenEdge.id))
        assertEquals(0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `returns 0 when chosen aura's owning skill is unslotted`() {
        val agg = aggregator(perks = listOf(sharpenEdge), slottedSkills = setOf(bow), chosenPerks = listOf(sharpenEdge.id))
        assertEquals(0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `single chosen aura on a slotted skill returns its magnitude`() {
        val agg = aggregator(perks = listOf(sharpenEdge), slottedSkills = setOf(sword), chosenPerks = listOf(sharpenEdge.id))
        assertEquals(5, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `multiple chosen auras on the same effect sum their magnitudes`() {
        val agg = aggregator(
            perks = listOf(sharpenEdge, precision),
            slottedSkills = setOf(sword),
            chosenPerks = listOf(sharpenEdge.id, precision.id),
        )
        assertEquals(15, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `auras targeting other effects do not contribute`() {
        val agg = aggregator(
            perks = listOf(sharpenEdge, swordFlatBlock),
            slottedSkills = setOf(sword),
            chosenPerks = listOf(sharpenEdge.id, swordFlatBlock.id),
        )
        assertEquals(5, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
        assertEquals(3, agg.bonusFor(agent, ScalingEffect.BLOCK_CHANCE))
        assertEquals(0, agg.bonusFor(agent, ScalingEffect.PIERCE_DAMAGE_BONUS))
    }

    @Test
    fun `auras across distinct slotted skills are summed independently`() {
        val agg = aggregator(
            perks = listOf(sharpenEdge, bowFlatPierce),
            slottedSkills = setOf(sword, bow),
            chosenPerks = listOf(sharpenEdge.id, bowFlatPierce.id),
        )
        assertEquals(5, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
        assertEquals(7, agg.bonusFor(agent, ScalingEffect.PIERCE_DAMAGE_BONUS))
    }

    @Test
    fun `non-aura perks are ignored even when their target matches`() {
        val agg = aggregator(
            perks = listOf(swordModifier),
            slottedSkills = setOf(sword),
            chosenPerks = listOf(swordModifier.id),
        )
        assertEquals(0, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    @Test
    fun `chosen perks pointing at unknown ids are skipped without throwing`() {
        val agg = aggregator(
            perks = listOf(sharpenEdge),
            slottedSkills = setOf(sword),
            chosenPerks = listOf(PerkId("PHANTOM"), sharpenEdge.id),
        )
        assertEquals(5, agg.bonusFor(agent, ScalingEffect.SLASH_DAMAGE_BONUS))
    }

    private fun aggregator(
        perks: List<Perk>,
        slottedSkills: Set<SkillId>,
        chosenPerks: List<PerkId>,
    ) = PassiveAuraAggregatorImpl(
        perks = StubPerkLookup(perks),
        skillsRegistry = StubSkillsRegistry(slottedSkills),
        perksRegistry = StubPerksRegistry(perks, chosenPerks),
    )

    private fun perk(id: String, skill: SkillId, effect: PerkEffect): Perk = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = 50,
        displayName = id,
        description = id,
        effect = effect,
    )

    private class StubPerkLookup(private val perks: List<Perk>) : PerkLookup {
        private val byId = perks.associateBy { it.id }
        override fun byId(id: PerkId): Perk? = byId[id]
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? = null
        override fun choicesFor(skill: SkillId): List<PerkChoice> = emptyList()
        override fun all(): List<Perk> = perks
    }

    private inner class StubSkillsRegistry(private val slotted: Set<SkillId>) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = slotted.mapIndexed { idx, skill ->
                skill to AgentSkillState(
                    skill = skill,
                    xp = 0,
                    level = 50,
                    slotIndex = idx,
                    recommendCount = 0,
                )
            }.toMap(),
            slotCount = slotted.size,
            slotsFilled = slotted.size,
        )

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private inner class StubPerksRegistry(perks: List<Perk>, private val chosen: List<PerkId>) : AgentPerksRegistry {
        private val skillByPerk = perks.associate { it.id to it.skill }

        override fun snapshot(agent: AgentId): AgentPerksSnapshot = AgentPerksSnapshot(
            perks = chosen.map { id ->
                AgentPerk(
                    skill = skillByPerk[id] ?: SkillId("UNKNOWN"),
                    milestoneLevel = 50,
                    perkId = id,
                    chosenAtTick = 0L,
                )
            },
        )

        override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult =
            RecordPerkResult.Recorded
    }
}
