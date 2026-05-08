package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PerkLookupImplTest {

    @Test
    fun `byId returns a Perk with effect translated to the matching sealed variant`() {
        val lookup = PerkLookupImpl(swordCatalog())

        val bleeder = assertNotNull(lookup.byId(PerkId("SWORD_BLEEDER")))
        assertEquals(SkillId("SWORD"), bleeder.skill)
        assertEquals(50, bleeder.milestoneLevel)
        val triggered = assertIs<PerkEffect.TriggeredPassive>(bleeder.effect)
        assertEquals(TriggeredPassiveTrigger.ON_HIT_DEALT, triggered.trigger)
        assertEquals(TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET, triggered.effectKind)
        assertEquals("BLEED", triggered.params["status"])
        assertEquals(8, triggered.internalCooldownTicks)

        val sharpen = assertNotNull(lookup.byId(PerkId("SWORD_SHARPEN_EDGE")))
        val aura = assertIs<PerkEffect.PassiveAura>(sharpen.effect)
        assertEquals(ScalingEffect.SLASH_DAMAGE_BONUS, aura.target)
        assertEquals(5, aura.magnitude)
    }

    @Test
    fun `byId returns an ActiveAbility perk with effectKind and params translated`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to SkillProperties(
                    displayName = "Sword",
                    description = "blade",
                    category = SkillCategory.COMBAT,
                    milestones = mapOf(
                        "100" to listOf(
                            PerkProperties(
                                id = "SWORD_POWER_STRIKE",
                                displayName = "Power Strike",
                                description = "1.5x next attack",
                                effect = PerkEffectProperties(
                                    type = PerkEffectType.ACTIVE_ABILITY,
                                    abilityId = "SWORD_POWER_STRIKE",
                                    costResource = AbilityCostResource.STAMINA,
                                    costAmount = 20,
                                    abilityTarget = AbilityTarget.SINGLE_AGENT,
                                    cooldownTicks = 5,
                                    abilityEffectKind = AbilityEffectKind.SCALE_NEXT_ATTACK,
                                    abilityEffectParams = mapOf("multiplierPct" to "150"),
                                ),
                            ),
                            passiveAuraPerk("SWORD_BUDDY"),
                        ),
                    ),
                ),
            ),
        )
        val lookup = PerkLookupImpl(props)

        val perk = assertNotNull(lookup.byId(PerkId("SWORD_POWER_STRIKE")))
        val active = assertIs<PerkEffect.ActiveAbility>(perk.effect)
        assertEquals(AbilityId("SWORD_POWER_STRIKE"), active.abilityId)
        assertEquals(AbilityCostResource.STAMINA, active.costResource)
        assertEquals(20, active.costAmount)
        assertEquals(AbilityTarget.SINGLE_AGENT, active.target)
        assertEquals(5, active.cooldownTicks)
        assertEquals(AbilityEffectKind.SCALE_NEXT_ATTACK, active.effectKind)
        assertEquals("150", active.effectParams["multiplierPct"])
    }

    @Test
    fun `byId returns null for unknown perk`() {
        assertNull(PerkLookupImpl(swordCatalog()).byId(PerkId("PHANTOM")))
    }

    @Test
    fun `choicesAt returns the binary fork at the requested milestone`() {
        val lookup = PerkLookupImpl(swordCatalog())
        val choice = assertNotNull(lookup.choicesAt(SkillId("SWORD"), 50))
        assertEquals(SkillId("SWORD"), choice.skill)
        assertEquals(50, choice.milestoneLevel)
        val ids = choice.options.map { it.id.value }.toSet()
        assertEquals(setOf("SWORD_BLEEDER", "SWORD_SHARPEN_EDGE"), ids)
    }

    @Test
    fun `choicesAt returns null for a milestone with no declared fork`() {
        assertNull(PerkLookupImpl(swordCatalog()).choicesAt(SkillId("SWORD"), 100))
    }

    @Test
    fun `choicesFor returns every declared fork for the skill, ordered by level`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to SkillProperties(
                    displayName = "Sword",
                    description = "blade",
                    category = SkillCategory.COMBAT,
                    milestones = mapOf(
                        "150" to listOf(passiveAuraPerk("S150_A"), passiveAuraPerk("S150_B")),
                        "50" to listOf(passiveAuraPerk("S50_A"), passiveAuraPerk("S50_B")),
                    ),
                ),
            ),
        )
        val lookup = PerkLookupImpl(props)
        val choices = lookup.choicesFor(SkillId("SWORD"))
        assertEquals(listOf(50, 150), choices.map { it.milestoneLevel })
    }

    @Test
    fun `init runs validation and throws on a bad catalog`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to SkillProperties(
                    displayName = "Sword",
                    description = "blade",
                    category = SkillCategory.COMBAT,
                    milestones = mapOf("50" to listOf(passiveAuraPerk("ONLY_ONE"))),
                ),
            ),
        )
        val ex = assertThrows<IllegalArgumentException> { PerkLookupImpl(props) }
        assertEquals(true, ex.message?.contains("Perk catalog failed validation"))
    }

    private fun swordCatalog() = SkillDefinitionProperties(
        catalog = mapOf(
            "SWORD" to SkillProperties(
                displayName = "Sword",
                description = "blade",
                category = SkillCategory.COMBAT,
                milestones = mapOf(
                    "50" to listOf(
                        PerkProperties(
                            id = "SWORD_BLEEDER",
                            displayName = "Bleeder",
                            description = "Apply Bleed on hit.",
                            effect = PerkEffectProperties(
                                type = PerkEffectType.TRIGGERED_PASSIVE,
                                trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
                                effectKind = TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET,
                                params = mapOf("status" to "BLEED", "duration-ticks" to "10"),
                                internalCooldownTicks = 8,
                            ),
                        ),
                        PerkProperties(
                            id = "SWORD_SHARPEN_EDGE",
                            displayName = "Sharpen Edge",
                            description = "Permanent +5 flat slash damage.",
                            effect = PerkEffectProperties(
                                type = PerkEffectType.PASSIVE_AURA,
                                auraTarget = ScalingEffect.SLASH_DAMAGE_BONUS,
                                auraMagnitude = 5,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun passiveAuraPerk(id: String) = PerkProperties(
        id = id,
        displayName = id,
        description = id,
        effect = PerkEffectProperties(
            type = PerkEffectType.PASSIVE_AURA,
            auraTarget = ScalingEffect.SLASH_DAMAGE_BONUS,
            auraMagnitude = 1,
        ),
    )
}
