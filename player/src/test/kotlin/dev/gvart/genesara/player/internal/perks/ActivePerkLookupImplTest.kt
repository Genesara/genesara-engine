package dev.gvart.genesara.player.internal.perks

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AbilityTarget
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ActivePerkLookupImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val sword = SkillId("SWORD")
    private val bow = SkillId("BOW")
    private val powerStrikeId = AbilityId("SWORD_POWER_STRIKE")

    private val powerStrikePerk = perk(
        id = "SWORD_POWER_STRIKE",
        skill = sword,
        effect = active(powerStrikeId),
    )
    private val sharpenAuraPerk = perk(
        id = "SWORD_SHARPEN_EDGE",
        skill = sword,
        effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 5),
    )

    @Test
    fun `byAbility returns the active perk when chosen and parent skill is slotted`() {
        val lookup = lookup(
            perks = listOf(powerStrikePerk),
            chosen = listOf(powerStrikePerk),
            slotted = mapOf(sword to 100),
        )
        val active = assertNotNull(lookup.byAbility(agent, powerStrikeId))
        assertEquals(powerStrikePerk.id, active.perk.id)
        assertEquals(powerStrikeId, active.effect.abilityId)
    }

    @Test
    fun `byAbility returns null when the ability id is unknown`() {
        val lookup = lookup(
            perks = listOf(powerStrikePerk),
            chosen = listOf(powerStrikePerk),
            slotted = mapOf(sword to 100),
        )
        assertNull(lookup.byAbility(agent, AbilityId("PHANTOM_ABILITY")))
    }

    @Test
    fun `byAbility returns null when the parent skill is not slotted`() {
        val lookup = lookup(
            perks = listOf(powerStrikePerk),
            chosen = listOf(powerStrikePerk),
            slotted = mapOf(bow to 50),
        )
        assertNull(lookup.byAbility(agent, powerStrikeId))
    }

    @Test
    fun `byAbility returns null when no perk has been chosen yet`() {
        val lookup = lookup(
            perks = listOf(powerStrikePerk),
            chosen = emptyList(),
            slotted = mapOf(sword to 100),
        )
        assertNull(lookup.byAbility(agent, powerStrikeId))
    }

    @Test
    fun `byAbility ignores non-active perk effects under the same skill`() {
        val lookup = lookup(
            perks = listOf(sharpenAuraPerk),
            chosen = listOf(sharpenAuraPerk),
            slotted = mapOf(sword to 50),
        )
        assertNull(lookup.byAbility(agent, powerStrikeId))
    }

    private fun lookup(perks: List<Perk>, chosen: List<Perk>, slotted: Map<SkillId, Int>) =
        ActivePerkLookupImpl(
            perks = StubPerkLookup(perks),
            perksRegistry = StubPerksRegistry(chosen),
            skillsRegistry = StubSkillsRegistry(slotted),
        )

    private fun perk(id: String, skill: SkillId, effect: PerkEffect): Perk = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = 100,
        displayName = id,
        description = id,
        effect = effect,
    )

    private fun active(abilityId: AbilityId): PerkEffect.ActiveAbility = PerkEffect.ActiveAbility(
        abilityId = abilityId,
        costResource = AbilityCostResource.STAMINA,
        costAmount = 20,
        target = AbilityTarget.SINGLE_AGENT,
        cooldownTicks = 5,
        effectKind = AbilityEffectKind.SCALE_NEXT_ATTACK,
        effectParams = mapOf("multiplierPct" to "150"),
    )

    private class StubPerkLookup(private val perks: List<Perk>) : PerkLookup {
        private val byId = perks.associateBy { it.id }
        override fun byId(id: PerkId): Perk? = byId[id]
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? = null
        override fun choicesFor(skill: SkillId): List<PerkChoice> = emptyList()
        override fun all(): List<Perk> = perks
    }

    private class StubPerksRegistry(private val chosen: List<Perk>) : AgentPerksRegistry {
        override fun snapshot(agent: AgentId): AgentPerksSnapshot = AgentPerksSnapshot(
            perks = chosen.map {
                AgentPerk(
                    skill = it.skill,
                    milestoneLevel = it.milestoneLevel,
                    perkId = it.id,
                    chosenAtTick = 0,
                )
            },
        )
        override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult =
            RecordPerkResult.Recorded
    }

    private class StubSkillsRegistry(private val slotted: Map<SkillId, Int>) : AgentSkillsRegistry {
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
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }
}
