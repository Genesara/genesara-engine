package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import org.springframework.stereotype.Component

/**
 * Canonical character-XP grant entry point. Wraps the atomic [AgentRegistry.addCharacterXp]
 * mutation and routes level-milestone transitions through the matching emitter so future
 * XP-source slices (quest rewards, NPC kills) only need to call this method.
 */
internal interface CharacterXpProgression {
    fun grant(agentId: AgentId, delta: Int): AddCharacterXpOutcome?

    companion object {
        /** Drop-in no-op for tests that exercise reducers but don't care about XP propagation. */
        val NoOp: CharacterXpProgression = object : CharacterXpProgression {
            override fun grant(agentId: AgentId, delta: Int): AddCharacterXpOutcome? = null
        }
    }
}

@Component
internal class DefaultCharacterXpProgression(
    private val agents: AgentRegistry,
    private val level10: Level10ChoiceEmitter,
    private val level50: Level50EvolutionEmitter,
    private val tickClock: TickClock,
) : CharacterXpProgression {

    override fun grant(agentId: AgentId, delta: Int): AddCharacterXpOutcome? {
        val outcome = agents.addCharacterXp(agentId, delta) ?: return null
        if (outcome is AddCharacterXpOutcome.Granted) {
            val tick = tickClock.currentTick()
            if (outcome.previousLevel < LEVEL_TEN_THRESHOLD && outcome.currentLevel >= LEVEL_TEN_THRESHOLD) {
                level10.tryEmitFor(agentId, tick)
            }
            if (outcome.previousLevel < LEVEL_FIFTY_THRESHOLD && outcome.currentLevel >= LEVEL_FIFTY_THRESHOLD) {
                level50.tryEmitFor(agentId, tick)
            }
        }
        return outcome
    }

    private companion object {
        const val LEVEL_TEN_THRESHOLD = 10
        const val LEVEL_FIFTY_THRESHOLD = 50
    }
}
