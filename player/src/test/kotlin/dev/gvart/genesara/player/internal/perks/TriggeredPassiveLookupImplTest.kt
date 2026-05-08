package dev.gvart.genesara.player.internal.perks

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
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggeredPassiveLookupImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val sword = SkillId("SWORD")
    private val bow = SkillId("BOW")

    private val bleeder = perk(
        id = "SWORD_BLEEDER",
        skill = sword,
        effect = triggered(TriggeredPassiveTrigger.ON_HIT_DEALT, TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET, cd = 8),
    )
    private val swordCrit = perk(
        id = "SWORD_CRIT_HEAL",
        skill = sword,
        effect = triggered(TriggeredPassiveTrigger.ON_CRIT, TriggeredPassiveEffectKind.HEAL_SELF, cd = 12),
    )
    private val bowKill = perk(
        id = "BOW_HUNT_BOUNTY",
        skill = bow,
        effect = triggered(TriggeredPassiveTrigger.ON_KILL, TriggeredPassiveEffectKind.GRANT_BONUS_ITEM, cd = 30),
    )
    private val sharpen = perk(
        id = "SWORD_SHARPEN_EDGE",
        skill = sword,
        effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 5),
    )

    @Test
    fun `returns empty when agent has chosen no perks`() {
        val lookup = lookup(
            perks = listOf(bleeder),
            chosen = emptyList(),
            slotted = mapOf(sword to 1),
        )
        assertTrue(lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT).isEmpty())
    }

    @Test
    fun `returns empty when no skill is slotted, even if perk is chosen`() {
        val lookup = lookup(
            perks = listOf(bleeder),
            chosen = listOf(bleeder),
            slotted = emptyMap(),
        )
        assertTrue(lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT).isEmpty())
    }

    @Test
    fun `returns empty when owning skill is not slotted`() {
        val lookup = lookup(
            perks = listOf(bleeder),
            chosen = listOf(bleeder),
            slotted = mapOf(bow to 1),
        )
        assertTrue(lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT).isEmpty())
    }

    @Test
    fun `returns the matching perk when chosen + slotted + trigger lines up`() {
        val lookup = lookup(
            perks = listOf(bleeder),
            chosen = listOf(bleeder),
            slotted = mapOf(sword to 50),
        )
        val result = lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT)
        assertEquals(1, result.size)
        assertEquals(bleeder.id, result.single().perk.id)
    }

    @Test
    fun `filters out perks bound to a different trigger`() {
        val lookup = lookup(
            perks = listOf(bleeder, swordCrit),
            chosen = listOf(bleeder, swordCrit),
            slotted = mapOf(sword to 100),
        )
        val onHit = lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT).map { it.perk.id }
        assertEquals(listOf(bleeder.id), onHit)

        val onCrit = lookup.matching(agent, TriggeredPassiveTrigger.ON_CRIT).map { it.perk.id }
        assertEquals(listOf(swordCrit.id), onCrit)
    }

    @Test
    fun `ignores non-TriggeredPassive effects`() {
        val lookup = lookup(
            perks = listOf(bleeder, sharpen),
            chosen = listOf(bleeder, sharpen),
            slotted = mapOf(sword to 50),
        )
        val result = lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT)
        assertEquals(listOf(bleeder.id), result.map { it.perk.id })
    }

    @Test
    fun `returns all matching perks across multiple slotted skills (no first-match-wins)`() {
        val swordOnKill = perk(
            id = "SWORD_VICTORY_ROAR",
            skill = sword,
            effect = triggered(TriggeredPassiveTrigger.ON_KILL, TriggeredPassiveEffectKind.GRANT_SELF_BUFF, cd = 5),
        )
        val lookup = lookup(
            perks = listOf(swordOnKill, bowKill),
            chosen = listOf(swordOnKill, bowKill),
            slotted = mapOf(sword to 100, bow to 100),
        )
        val result = lookup.matching(agent, TriggeredPassiveTrigger.ON_KILL).map { it.perk.id }.toSet()
        assertEquals(setOf(swordOnKill.id, bowKill.id), result)
    }

    private fun lookup(perks: List<Perk>, chosen: List<Perk>, slotted: Map<SkillId, Int>) =
        TriggeredPassiveLookupImpl(
            perks = StubPerkLookup(perks),
            perksRegistry = StubPerksRegistry(chosen),
            skillsRegistry = StubSkillsRegistry(slotted),
        )

    private fun perk(id: String, skill: SkillId, effect: PerkEffect): Perk = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = 50,
        displayName = id,
        description = id,
        effect = effect,
    )

    private fun triggered(
        trigger: TriggeredPassiveTrigger,
        effectKind: TriggeredPassiveEffectKind,
        cd: Int,
    ) = PerkEffect.TriggeredPassive(
        trigger = trigger,
        effectKind = effectKind,
        params = emptyMap(),
        internalCooldownTicks = cd,
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
            perks = chosen.map { p ->
                AgentPerk(
                    skill = p.skill,
                    milestoneLevel = p.milestoneLevel,
                    perkId = p.id,
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
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult =
            AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }
}
