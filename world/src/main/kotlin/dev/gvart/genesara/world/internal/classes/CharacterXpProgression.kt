package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Canonical character-XP grant entry point. Wraps the atomic [AgentRegistry.addCharacterXp]
 * mutation, publishes [AgentEvent.CharacterXpGained] (always) and [AgentEvent.AgentLeveled]
 * (when the grant cascade crossed at least one level boundary), and routes level-milestone
 * transitions through the matching emitter so future XP-source slices (quest rewards, NPC
 * kills) only need to call this method.
 */
internal interface CharacterXpProgression {
    fun grant(agentId: AgentId, source: CharacterXpSource, delta: Int, commandId: UUID): AddCharacterXpOutcome?

    companion object {
        /** Drop-in no-op for tests that exercise reducers but don't care about XP propagation. */
        val NoOp: CharacterXpProgression = object : CharacterXpProgression {
            override fun grant(
                agentId: AgentId,
                source: CharacterXpSource,
                delta: Int,
                commandId: UUID,
            ): AddCharacterXpOutcome? = null
        }
    }
}

@Component
internal class DefaultCharacterXpProgression(
    private val agents: AgentRegistry,
    private val level10: Level10ChoiceEmitter,
    private val level50: Level50EvolutionEmitter,
    private val tickClock: TickClock,
    private val publisher: ApplicationEventPublisher,
) : CharacterXpProgression {

    override fun grant(
        agentId: AgentId,
        source: CharacterXpSource,
        delta: Int,
        commandId: UUID,
    ): AddCharacterXpOutcome? {
        val outcome = agents.addCharacterXp(agentId, delta) ?: return null
        if (outcome is AddCharacterXpOutcome.Granted) {
            val tick = tickClock.currentTick()
            publisher.publishEvent(
                AgentEvent.CharacterXpGained(
                    agent = agentId,
                    source = source,
                    amount = outcome.accruedDelta,
                    total = outcome.xpCurrent,
                    toNext = outcome.xpToNext,
                    level = outcome.currentLevel,
                    unspentAttributePoints = outcome.unspentAttributePoints,
                    tick = tick,
                    causedBy = commandId,
                ),
            )
            if (outcome.currentLevel > outcome.previousLevel) {
                publisher.publishEvent(
                    AgentEvent.AgentLeveled(
                        agent = agentId,
                        fromLevel = outcome.previousLevel,
                        toLevel = outcome.currentLevel,
                        unspentAttributePoints = outcome.unspentAttributePoints,
                        tick = tick,
                        causedBy = commandId,
                    ),
                )
            }
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
