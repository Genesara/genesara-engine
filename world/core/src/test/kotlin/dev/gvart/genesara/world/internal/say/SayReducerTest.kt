package dev.gvart.genesara.world.internal.say

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class SayReducerTest {

    private val regionId = dev.gvart.genesara.world.RegionId(1L)
    private val speaker = agent("speaker")

    private val nA = NodeId(1L)
    private val nB = NodeId(2L)
    private val nC = NodeId(3L)
    private val nD = NodeId(4L)
    private val nE = NodeId(5L)
    private val nF = NodeId(6L)
    private val nG = NodeId(7L)

    @Test
    fun `WHISPER fan-out reaches only the speaker's node and direct neighbours`() {
        val nearA = agent("a")
        val nearB = agent("b")
        val twoAway = agent("c")
        val state = lineState(positions = mapOf(
            speaker to nA, nearA to nA, nearB to nB, twoAway to nC,
        ))

        val result = reduceSay(state.core, sayCommand(SpeechMode.WHISPER), defaultBalance(), tick = 1)

        val event = singleSpoke(result)
        assertEquals(setOf(speaker, nearA, nearB), event.listeners)
    }

    @Test
    fun `NORMAL fan-out reaches three hops out, excludes node four away`() {
        val atC = agent("c")
        val atD = agent("d")
        val atE = agent("e")
        val state = lineState(positions = mapOf(
            speaker to nA, atC to nC, atD to nD, atE to nE,
        ))

        val result = reduceSay(state.core, sayCommand(SpeechMode.NORMAL), defaultBalance(), tick = 1)

        val event = singleSpoke(result)
        assertEquals(setOf(speaker, atC, atD), event.listeners)
    }

    @Test
    fun `SCREAM fan-out reaches five hops out, excludes node six away`() {
        val atE = agent("e")
        val atF = agent("f")
        val atG = agent("g")
        val state = lineState(positions = mapOf(
            speaker to nA, atE to nE, atF to nF, atG to nG,
        ))

        val result = reduceSay(state.core, sayCommand(SpeechMode.SCREAM), defaultBalance(), tick = 1)

        val event = singleSpoke(result)
        assertEquals(setOf(speaker, atE, atF), event.listeners)
    }

    @Test
    fun `speaker hears themselves even when alone in the world`() {
        val state = lineState(positions = mapOf(speaker to nA))

        val result = reduceSay(state.core, sayCommand(SpeechMode.NORMAL), defaultBalance(), tick = 1)

        val event = singleSpoke(result)
        assertEquals(setOf(speaker), event.listeners)
    }

    @Test
    fun `event carries the message, mode, channel, origin node, tick and causedBy`() {
        val state = lineState(positions = mapOf(speaker to nA))
        val command = CoreCommand.Say(speaker, "hello world", SpeechMode.WHISPER, SayChannel.LOCAL)

        val result = reduceSay(state.core, command, defaultBalance(), tick = 42)

        val event = singleSpoke(result)
        assertEquals(speaker, event.speaker)
        assertEquals(nA, event.at)
        assertEquals("hello world", event.message)
        assertEquals(SpeechMode.WHISPER, event.mode)
        assertEquals(SayChannel.LOCAL, event.channel)
        assertEquals(42L, event.tick)
        assertEquals(command.commandId, event.causedBy)
    }

    @Test
    fun `state is unchanged on success - say is observation, not mutation`() {
        val state = lineState(positions = mapOf(speaker to nA, agent("b") to nB))

        val out = assertNotNull(
            reduceSay(state.core, sayCommand(SpeechMode.NORMAL), defaultBalance(), tick = 1).getOrNull()
        )

        assertSame(state.core, out.sliceDelta)
    }

    @Test
    fun `rejects with MessageTooLong when length exceeds the balance cap`() {
        val state = lineState(positions = mapOf(speaker to nA))
        val balance = balance(maxLength = 10)
        val command = CoreCommand.Say(speaker, "x".repeat(11), SpeechMode.NORMAL, SayChannel.LOCAL)

        val result = reduceSay(state.core, command, balance, tick = 1)

        assertEquals(WorldRejection.MessageTooLong(speaker, length = 11, max = 10), result.leftOrNull())
    }

    @Test
    fun `accepts exactly the balance cap length without rejecting`() {
        val state = lineState(positions = mapOf(speaker to nA))
        val balance = balance(maxLength = 10)
        val command = CoreCommand.Say(speaker, "x".repeat(10), SpeechMode.NORMAL, SayChannel.LOCAL)

        val result = reduceSay(state.core, command, balance, tick = 1)

        assertNotNull(result.getOrNull())
    }

    @Test
    fun `rejects with NotInWorld when speaker has no position`() {
        val state = lineState(positions = emptyMap())

        val result = reduceSay(state.core, sayCommand(SpeechMode.NORMAL), defaultBalance(), tick = 1)

        assertEquals(WorldRejection.NotInWorld(speaker), result.leftOrNull())
    }

    @Test
    fun `not-in-world rejection wins over message-too-long when both fail`() {
        val state = lineState(positions = emptyMap())
        val balance = balance(maxLength = 5)
        val command = CoreCommand.Say(speaker, "too long", SpeechMode.NORMAL, SayChannel.LOCAL)

        val result = reduceSay(state.core, command, balance, tick = 1)

        assertEquals(WorldRejection.NotInWorld(speaker), result.leftOrNull())
    }

    @Test
    fun `diamond graph dedups listeners reached via multiple paths`() {
        val center = NodeId(100L)
        val northeast = NodeId(101L)
        val northwest = NodeId(102L)
        val far = NodeId(103L)
        val atFar = agent("at-far")
        val nodes = mapOf(
            center to Node(center, regionId, 0, 0, Terrain.PLAINS, adjacency = setOf(northeast, northwest)),
            northeast to Node(northeast, regionId, 1, 0, Terrain.PLAINS, adjacency = setOf(center, far)),
            northwest to Node(northwest, regionId, -1, 0, Terrain.PLAINS, adjacency = setOf(center, far)),
            far to Node(far, regionId, 0, 2, Terrain.PLAINS, adjacency = setOf(northeast, northwest)),
        )
        val state = WorldState.EMPTY.copy(
            nodes = nodes,
            positions = mapOf(speaker to center, atFar to far),
        )

        val result = reduceSay(state.core, sayCommand(SpeechMode.WHISPER), defaultBalance(), tick = 1)

        val event = singleSpoke(result)
        // far is 2 hops away on either path — must NOT leak in via the join.
        assertEquals(setOf(speaker), event.listeners)
    }

    @Test
    fun `speaker positioned on a node missing from state nodes hears only themselves`() {
        // Orphan position contract: a position pointing at a node not in state.nodes is a
        // data-inconsistency we degrade gracefully rather than crash on.
        val orphan = NodeId(999L)
        val onlooker = agent("onlooker")
        val state = WorldState.EMPTY.copy(
            nodes = mapOf(nA to Node(nA, regionId, 0, 0, Terrain.PLAINS, adjacency = emptySet())),
            positions = mapOf(speaker to orphan, onlooker to nA),
        )

        val result = reduceSay(state.core, sayCommand(SpeechMode.SCREAM), defaultBalance(), tick = 1)

        val event = singleSpoke(result)
        assertEquals(setOf(speaker), event.listeners)
        assertEquals(orphan, event.at)
    }

    private fun singleSpoke(
        result: arrow.core.Either<WorldRejection, dev.gvart.genesara.world.internal.worldstate.ReducerOutput<dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice>>,
    ): CoreEvent.AgentSpoke {
        val out = assertNotNull(result.getOrNull())
        return assertIs<CoreEvent.AgentSpoke>(out.events.single())
    }

    private fun sayCommand(mode: SpeechMode) =
        CoreCommand.Say(speaker, "hi", mode, SayChannel.LOCAL)

    private fun lineState(positions: Map<AgentId, NodeId>): WorldState {
        val ids = listOf(nA, nB, nC, nD, nE, nF, nG)
        val nodes = ids.mapIndexed { idx, id ->
            val neighbours = listOfNotNull(ids.getOrNull(idx - 1), ids.getOrNull(idx + 1)).toSet()
            id to Node(id, regionId, q = idx, r = 0, terrain = Terrain.PLAINS, adjacency = neighbours)
        }.toMap()
        return WorldState.EMPTY.copy(
            core = WorldState.EMPTY.core.copy(nodes = nodes, positions = positions),
        )
    }

    private fun agent(label: String) = AgentId(UUID.nameUUIDFromBytes(label.toByteArray()))

    private fun defaultBalance(): BalanceLookup = balance(maxLength = 500)

    private fun balance(maxLength: Int): BalanceLookup = object : BalanceLookup {
        override fun maxSayMessageLength(): Int = maxLength
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 0
        override fun staminaRegenPerTick(climate: Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 0
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 0
        override fun drinkThirstRefill(): Int = 0
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
    }
}
