package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeDerivation
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.BodyCommand
import dev.gvart.genesara.world.commands.WorldCommand
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class DerivedPoolsRefresherImplTest {

    private val agent = AgentId(UUID.randomUUID())

    @Test
    fun `refresh queues RefreshDerivedPools at the next tick with effective-attribute pools`() {
        val base = AgentAttributes(constitution = 4, intelligence = 2)
        val agents = StubAgentRegistry(base)
        val bonuses = ConCharmingBonuses(plusCon = 3, plusInt = 1)
        val gateway = RecordingGateway()
        val tickClock = StubTickClock(currentTick = 11)
        val refresher = DerivedPoolsRefresherImpl(agents, bonuses, gateway, tickClock)

        refresher.refresh(agent)

        val expected = AttributeDerivation.deriveMaxPools(
            AgentAttributes(constitution = 7, intelligence = 3),
        )
        val submitted = assertIs<BodyCommand.RefreshDerivedPools>(gateway.lastCommand)
        assertEquals(agent, submitted.agent)
        assertEquals(expected.maxHp, submitted.maxHp)
        assertEquals(expected.maxStamina, submitted.maxStamina)
        assertEquals(expected.maxMana, submitted.maxMana)
        assertEquals(12L, gateway.lastAppliesAtTick)
    }

    @Test
    fun `refresh throws when the agent is missing from the registry — surfaces upstream invariant break`() {
        val agents = StubAgentRegistry(null)
        val refresher = DerivedPoolsRefresherImpl(
            agents,
            EquipmentBonusAggregator.NoBonuses,
            RecordingGateway(),
            StubTickClock(currentTick = 0),
        )

        kotlin.test.assertFailsWith<IllegalStateException> { refresher.refresh(agent) }
    }

    private class StubAgentRegistry(private val attrs: AgentAttributes?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (attrs == null) null else Agent(
            id = id, owner = PlayerId(UUID.randomUUID()), name = "test", attributes = attrs,
        )

        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
    }

    private class ConCharmingBonuses(
        private val plusCon: Int,
        private val plusInt: Int,
    ) : EquipmentBonusAggregator {
        override fun armorDef(agent: AgentId, damageType: DamageType): Int = 0
        override fun attributeBonus(agent: AgentId, attribute: Attribute): Int = when (attribute) {
            Attribute.CONSTITUTION -> plusCon
            Attribute.INTELLIGENCE -> plusInt
            else -> 0
        }
        override fun passiveBuff(agent: AgentId, effect: ScalingEffect): Int = 0
    }

    private class RecordingGateway : WorldCommandGateway {
        var lastCommand: WorldCommand? = null
        var lastAppliesAtTick: Long = -1
        override fun submit(command: WorldCommand, appliesAtTick: Long): Long {
            lastCommand = command
            lastAppliesAtTick = appliesAtTick
            return appliesAtTick
        }
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }
}
