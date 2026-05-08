package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerksValidatorTest {

    @Test
    fun `accepts a well-formed catalog with no perks`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf("FORAGING" to skill()),
        )
        assertEquals(emptyList(), PerksValidator.collectProblems(props))
    }

    @Test
    fun `accepts a well-formed milestone fork`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(
                            triggeredPassivePerk("SWORD_BLEEDER"),
                            passiveAuraPerk("SWORD_SHARPEN_EDGE"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), PerksValidator.collectProblems(props))
    }

    @Test
    fun `rejects a milestone with one perk — must be a 1-of-2 fork`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf("50" to listOf(passiveAuraPerk("SOLO"))),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(problems.any { it.contains("expected exactly 2 perks") }, problems.toString())
    }

    @Test
    fun `rejects an illegal milestone level`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "75" to listOf(
                            passiveAuraPerk("SWORD_A"),
                            passiveAuraPerk("SWORD_B"),
                        ),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(problems.any { it.contains("milestone level must be one of") }, problems.toString())
    }

    @Test
    fun `rejects a non-numeric milestone key`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "fifty" to listOf(passiveAuraPerk("A"), passiveAuraPerk("B")),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(problems.any { it.contains("not an integer") }, problems.toString())
    }

    @Test
    fun `rejects perk-id collisions across skills`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(passiveAuraPerk("DUPLICATE"), passiveAuraPerk("UNIQUE_A")),
                    ),
                ),
                "BOW" to skill(
                    milestones = mapOf(
                        "50" to listOf(passiveAuraPerk("DUPLICATE"), passiveAuraPerk("UNIQUE_B")),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(
            problems.any { it.contains("DUPLICATE") && it.contains("declared in multiple places") },
            problems.toString(),
        )
    }

    @Test
    fun `rejects an active-ability perk missing required fields`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(
                            PerkProperties(
                                id = "BAD_ACTIVE",
                                displayName = "Bad",
                                description = "Bad",
                                effect = PerkEffectProperties(type = PerkEffectType.ACTIVE_ABILITY),
                            ),
                            passiveAuraPerk("OK"),
                        ),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(problems.any { it.contains("ability-id is required") }, problems.toString())
        assertTrue(problems.any { it.contains("cost-resource is required") }, problems.toString())
    }

    @Test
    fun `rejects a perk with a missing effect type`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(
                            PerkProperties(
                                id = "NO_TYPE",
                                displayName = "X",
                                description = "X",
                                effect = PerkEffectProperties(),
                            ),
                            passiveAuraPerk("OK"),
                        ),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(problems.any { it.contains("effect.type is required") }, problems.toString())
    }

    @Test
    fun `rejects a perk with blank id, display-name, description`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(
                            PerkProperties(effect = passiveAuraEffect()),
                            passiveAuraPerk("OK"),
                        ),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertTrue(problems.any { it.contains("perk id is blank") }, problems.toString())
        assertTrue(problems.any { it.contains("missing display-name") }, problems.toString())
        assertTrue(problems.any { it.contains("missing description") }, problems.toString())
    }

    @Test
    fun `rejects a passive-aura perk with non-positive magnitude`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(
                            PerkProperties(
                                id = "ZERO_AURA",
                                displayName = "Zero",
                                description = "Zero",
                                effect = PerkEffectProperties(
                                    type = PerkEffectType.PASSIVE_AURA,
                                    auraTarget = ScalingEffect.SLASH_DAMAGE_BONUS,
                                    auraMagnitude = 0,
                                ),
                            ),
                            PerkProperties(
                                id = "NEG_AURA",
                                displayName = "Neg",
                                description = "Neg",
                                effect = PerkEffectProperties(
                                    type = PerkEffectType.PASSIVE_AURA,
                                    auraTarget = ScalingEffect.SLASH_DAMAGE_BONUS,
                                    auraMagnitude = -5,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val problems = PerksValidator.collectProblems(props)
        assertEquals(2, problems.count { it.contains("aura-magnitude: must be > 0") }, problems.toString())
    }

    @Test
    fun `valid active-ability perk parses without problems`() {
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to skill(
                    milestones = mapOf(
                        "50" to listOf(activeAbilityPerk("SWORD_LUNGE"), passiveAuraPerk("PARTNER")),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), PerksValidator.collectProblems(props))
    }

    private fun skill(
        milestones: Map<String, List<PerkProperties>> = emptyMap(),
    ) = SkillProperties(
        displayName = "stub",
        description = "stub",
        category = SkillCategory.COMBAT,
        milestones = milestones,
    )

    private fun passiveAuraEffect() = PerkEffectProperties(
        type = PerkEffectType.PASSIVE_AURA,
        auraTarget = ScalingEffect.SLASH_DAMAGE_BONUS,
        auraMagnitude = 1,
    )

    private fun passiveAuraPerk(id: String) = PerkProperties(
        id = id,
        displayName = id,
        description = id,
        effect = passiveAuraEffect(),
    )

    private fun triggeredPassivePerk(id: String) = PerkProperties(
        id = id,
        displayName = id,
        description = id,
        effect = PerkEffectProperties(
            type = PerkEffectType.TRIGGERED_PASSIVE,
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            effectKind = TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET,
            params = mapOf("status" to "BLEED"),
            internalCooldownTicks = 8,
        ),
    )

    private fun activeAbilityPerk(id: String) = PerkProperties(
        id = id,
        displayName = id,
        description = id,
        effect = PerkEffectProperties(
            type = PerkEffectType.ACTIVE_ABILITY,
            abilityId = "stub_ability",
            costResource = AbilityCostResource.STAMINA,
            costAmount = 10,
            abilityTarget = AbilityTarget.SELF,
            cooldownTicks = 30,
        ),
    )
}
