package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import org.springframework.stereotype.Component

/**
 * Canonical character-XP grant entry point. Wraps the atomic
 * [AgentRegistry.addCharacterXp] mutation and routes any 9 → 10 transition
 * through [Level10ChoiceEmitter] so future XP-source slices (quest rewards,
 * NPC kills) only need to call this method.
 *
 * Currently no reducer wires up to this class — Phase-1 verbs only grant
 * skill XP via `SkillProgression`. The component exists so the level-10 flow
 * is end-to-end exercisable from integration tests, and so the wiring point
 * is fixed for the first XP-source slice that lands.
 */
@Component
internal class CharacterXpProgression(
    private val agents: AgentRegistry,
    private val level10: Level10ChoiceEmitter,
    private val tickClock: TickClock,
) {

    fun grant(agentId: AgentId, delta: Int): AddCharacterXpOutcome? {
        val outcome = agents.addCharacterXp(agentId, delta) ?: return null
        if (outcome is AddCharacterXpOutcome.Granted &&
            outcome.previousLevel < LEVEL_TEN_THRESHOLD &&
            outcome.currentLevel >= LEVEL_TEN_THRESHOLD
        ) {
            level10.tryEmitFor(agentId, tickClock.currentTick())
        }
        return outcome
    }

    private companion object {
        const val LEVEL_TEN_THRESHOLD = 10
    }
}
