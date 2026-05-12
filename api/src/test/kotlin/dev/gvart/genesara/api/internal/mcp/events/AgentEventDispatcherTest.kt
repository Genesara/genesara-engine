package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals

class AgentEventDispatcherTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val bus: InvalidationBus = mock(InvalidationBus::class.java)
    private val log = FakeAgentEventLog()
    private val dispatcher = AgentEventDispatcher(bus, log, mapper)

    private val agent = AgentId(UUID.randomUUID())

    @Test
    fun `AgentMoved appends an envelope and pings the per-agent resource URI`() {
        val cmdId = UUID.randomUUID()
        val event = WorldEvent.AgentMoved(agent, NodeId(1L), NodeId(2L), tick = 5, causedBy = cmdId)

        dispatcher.on(event)

        val all = log.since(agent, 0)
        assertEquals(1, all.size)
        val envelope = all.single()
        assertEquals("agent.moved", envelope.type)
        assertEquals(5L, envelope.tick)
        assertEquals(1L, envelope.seq)
        assertEquals(cmdId.toString(), envelope.payload.get("causedBy").asString())

        verify(bus).publish(InvalidationMessage.AgentNotify(agent))
    }

    @Test
    fun `AgentDespawned publishes the event and keeps the log readable for cursor-based replay`() {
        val cmdId = UUID.randomUUID()
        // Pre-existing event from before the despawn
        val pre = log.append(agent, "agent.moved", 1L, mapper.createObjectNode())

        dispatcher.on(WorldEvent.AgentDespawned(agent, NodeId(7L), tick = 9, causedBy = cmdId))

        // Both events remain in the log so a slightly-late client can still drain via cursor.
        val all = log.since(agent, 0)
        assertEquals(2, all.size)
        assertEquals("agent.moved", all[0].type)
        assertEquals("agent.despawned", all[1].type)
        // Reading after the pre-despawn event yields only the despawned envelope.
        val tail = log.since(agent, pre.seq)
        assertEquals(1, tail.size)
        assertEquals("agent.despawned", tail.single().type)
    }

    @Test
    fun `monotonic seq across appends`() {
        repeat(3) { i ->
            dispatcher.on(WorldEvent.AgentMoved(agent, NodeId(1L), NodeId(2L), tick = i.toLong(), causedBy = UUID.randomUUID()))
        }
        val seqs = log.since(agent, 0).map { it.seq }
        assertEquals(listOf(1L, 2L, 3L), seqs)
    }

    @Test
    fun `command-outcome events are forwarded to the agent's stream with causedBy intact`() {
        // The Drink, Harvest, and Consume verbs all produce per-command outcome events. They
        // must reach the agent's stream so the agent can correlate them with the originating
        // commandId. A regression here would silently break the ack/event protocol.
        val harvestCmd = UUID.randomUUID()
        val consumeCmd = UUID.randomUUID()
        val drinkCmd = UUID.randomUUID()

        dispatcher.on(WorldEvent.ResourceHarvested(agent, NodeId(1L), ItemId("WOOD"), quantity = 1, tick = 1, causedBy = harvestCmd))
        dispatcher.on(WorldEvent.ItemConsumed(agent, ItemId("BERRY"), Gauge.HUNGER, refilled = 20, tick = 2, causedBy = consumeCmd))
        dispatcher.on(WorldEvent.AgentDrank(agent, NodeId(1L), refilled = 25, tick = 3, causedBy = drinkCmd))

        val all = log.since(agent, 0)
        assertEquals(listOf("resource.harvested", "item.consumed", "agent.drank"), all.map { it.type })
        assertEquals(harvestCmd.toString(), all[0].payload.get("causedBy").asString())
        assertEquals(consumeCmd.toString(), all[1].payload.get("causedBy").asString())
        assertEquals(drinkCmd.toString(), all[2].payload.get("causedBy").asString())
    }

    @Test
    fun `SkillMilestoneReached and SkillRecommended events reach the agent's stream`() {
        val cmdId = UUID.randomUUID()
        val recCmdId = UUID.randomUUID()
        dispatcher.on(
            AgentEvent.SkillMilestoneReached(
                agent = agent,
                skill = SkillId("FORAGING"),
                milestone = 50,
                tick = 5,
                causedBy = cmdId,
            ),
        )
        dispatcher.on(
            AgentEvent.SkillRecommended(
                agent = agent,
                skill = SkillId("MINING"),
                recommendCount = 1,
                slotsFree = 8,
                tick = 6,
                causedBy = recCmdId,
            ),
        )

        val all = log.since(agent, 0)
        assertEquals(listOf("skill.milestone", "skill.recommended"), all.map { it.type })
        // Locks the AgentEvent branch in publish()'s tick cast — without this assert,
        // dropping that branch would silently log AgentEvent envelopes with tick=0.
        assertEquals(5L, all[0].tick)
        assertEquals(6L, all[1].tick)
        // The whole payload should at minimum carry the milestone fields. Asserting on
        // the structural shape is fragile across Jackson versions for value classes; the
        // important contract is that the event lands and types are right.
        assertEquals(50, all[0].payload.get("milestone").asInt())
        assertEquals(1, all[1].payload.get("recommendCount").asInt())
        assertEquals(8, all[1].payload.get("slotsFree").asInt())
        // Skill payload is non-null in both — exact serialised form is left to Jackson.
        kotlin.test.assertNotNull(all[0].payload.get("skill"))
        kotlin.test.assertNotNull(all[1].payload.get("skill"))
    }

    @Test
    fun `CharacterXpGained and AgentLeveled reach the agent's stream with the right type strings`() {
        val cmdId = UUID.randomUUID()
        dispatcher.on(
            AgentEvent.CharacterXpGained(
                agent = agent,
                source = dev.gvart.genesara.player.CharacterXpSource.HARVEST,
                amount = 3,
                total = 17,
                toNext = 100,
                level = 1,
                unspentAttributePoints = 0,
                tick = 11,
                causedBy = cmdId,
            ),
        )
        dispatcher.on(
            AgentEvent.AgentLeveled(
                agent = agent,
                fromLevel = 1,
                toLevel = 2,
                unspentAttributePoints = 5,
                tick = 12,
                causedBy = cmdId,
            ),
        )

        val all = log.since(agent, 0)
        assertEquals(listOf("agent.xp_gained", "agent.leveled"), all.map { it.type })
        assertEquals(11L, all[0].tick)
        assertEquals(12L, all[1].tick)
        assertEquals("HARVEST", all[0].payload.get("source").asString())
        assertEquals(3, all[0].payload.get("amount").asInt())
        assertEquals(17, all[0].payload.get("total").asInt())
        assertEquals(100, all[0].payload.get("toNext").asInt())
        assertEquals(1, all[0].payload.get("level").asInt())
        assertEquals(cmdId.toString(), all[0].payload.get("causedBy").asString())
        assertEquals(1, all[1].payload.get("fromLevel").asInt())
        assertEquals(2, all[1].payload.get("toLevel").asInt())
        assertEquals(5, all[1].payload.get("unspentAttributePoints").asInt())
        assertEquals(cmdId.toString(), all[1].payload.get("causedBy").asString())
    }

    @Test
    fun `AttributeMilestoneReached reaches the agent's stream`() {
        dispatcher.on(
            AgentEvent.AttributeMilestoneReached(
                agent = agent,
                attribute = dev.gvart.genesara.player.Attribute.INTELLIGENCE,
                milestone = 100,
                tick = 9,
            ),
        )

        val entry = log.since(agent, 0).single()
        assertEquals("attribute.milestone", entry.type)
        assertEquals(9L, entry.tick)
        assertEquals(100, entry.payload.get("milestone").asInt())
        assertEquals("INTELLIGENCE", entry.payload.get("attribute").asString())
    }

    @Test
    fun `AgentAttacked fans out to both attacker and target streams`() {
        val attacker = AgentId(UUID.randomUUID())
        val target = AgentId(UUID.randomUUID())
        val cmdId = UUID.randomUUID()
        val event = WorldEvent.AgentAttacked(
            attacker = attacker,
            target = target,
            at = NodeId(1L),
            damageType = DamageType.SLASH,
            baseDamage = 10,
            hpLost = 10,
            isCrit = false,
            isDodged = false,
            targetHpAfter = 40,
            targetKilled = false,
            tick = 12,
            causedBy = cmdId,
        )

        dispatcher.on(event)

        assertEquals(1, log.since(attacker, 0).size)
        assertEquals(1, log.since(target, 0).size)
        assertEquals("agent.attacked", log.since(attacker, 0).single().type)
        assertEquals("agent.attacked", log.since(target, 0).single().type)
    }

    @Test
    fun `AgentAttacked publishes only once when attacker equals target (defensive)`() {
        // Reducer rejects self-attacks before they reach the dispatcher, but if a future
        // ability ever fires AgentAttacked with attacker == target, the dispatcher must not
        // double-publish into the same stream and inflate envelope counts.
        val cmdId = UUID.randomUUID()
        val event = WorldEvent.AgentAttacked(
            attacker = agent,
            target = agent,
            at = NodeId(1L),
            damageType = DamageType.BLUNT,
            baseDamage = 0,
            hpLost = 0,
            isCrit = false,
            isDodged = false,
            targetHpAfter = 50,
            targetKilled = false,
            tick = 1,
            causedBy = cmdId,
        )

        dispatcher.on(event)

        assertEquals(1, log.since(agent, 0).size)
    }

    @Test
    fun `AgentDied lands on the dying agent's stream`() {
        val cmdId = UUID.randomUUID()
        val event = WorldEvent.AgentDied(
            agent = agent,
            at = NodeId(1L),
            xpLost = 25,
            deleveled = false,
            attributePointLost = null,
            tick = 12,
            causedBy = cmdId,
        )

        dispatcher.on(event)

        val entry = log.since(agent, 0).single()
        assertEquals("agent.died", entry.type)
        assertEquals(12L, entry.tick)
    }

    @Test
    fun `SafeNodeSet reaches the agent stream`() {
        val cmdId = UUID.randomUUID()
        dispatcher.on(WorldEvent.SafeNodeSet(agent, NodeId(525L), tick = 4L, causedBy = cmdId))

        val entry = log.since(agent, 0).single()
        assertEquals("agent.safe_node_set", entry.type)
        assertEquals(4L, entry.tick)
        assertEquals(cmdId.toString(), entry.payload.get("causedBy").asString())
    }

    @Test
    fun `AgentRespawned reaches the agent stream`() {
        val cmdId = UUID.randomUUID()
        dispatcher.on(WorldEvent.AgentRespawned(agent, NodeId(1L), fromCheckpoint = true, tick = 9L, causedBy = cmdId))

        val entry = log.since(agent, 0).single()
        assertEquals("agent.respawned", entry.type)
    }

    @Test
    fun `chest transfer events reach the agent stream`() {
        val deposit = UUID.randomUUID()
        val withdraw = UUID.randomUUID()
        val chest = UUID.randomUUID()
        dispatcher.on(WorldEvent.ItemDeposited(agent, chest, ItemId("WOOD"), quantity = 3, tick = 1L, causedBy = deposit))
        dispatcher.on(WorldEvent.ItemWithdrawn(agent, chest, ItemId("WOOD"), quantity = 2, tick = 2L, causedBy = withdraw))

        val types = log.since(agent, 0).map { it.type }
        assertEquals(listOf("item.deposited", "item.withdrawn"), types)
    }

    @Test
    fun `PassivesApplied fans out one envelope per affected agent`() {
        val a1 = AgentId(UUID.randomUUID())
        val a2 = AgentId(UUID.randomUUID())
        val event = WorldEvent.PassivesApplied(
            deltas = mapOf(
                a1 to dev.gvart.genesara.world.BodyDelta(stamina = 1),
                a2 to dev.gvart.genesara.world.BodyDelta(hp = 2),
            ),
            tick = 3L,
        )

        dispatcher.on(event)

        assertEquals(1, log.since(a1, 0).size)
        assertEquals(1, log.since(a2, 0).size)
        verify(bus).publish(InvalidationMessage.AgentNotify(a1))
        verify(bus).publish(InvalidationMessage.AgentNotify(a2))
    }
}
