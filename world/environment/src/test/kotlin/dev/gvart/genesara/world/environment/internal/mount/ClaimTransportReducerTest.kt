package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ClaimTransportReducerTest {

    private val claimer = AgentId(UUID.randomUUID())
    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)

    @Test
    fun `claimer at same node under cap takes ownership and emits TransportClaimed`() {
        val mount = mount(owner = null, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(claimer to nodeA))

        val result = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = mount.id),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 0),
            mounts = store,
            tick = 9L,
        ).getOrNull()

        val output = assertNotNull(result)
        val event = output.events.filterIsInstance<EnvironmentEvent.TransportClaimed>().single()
        assertEquals(mount.id, event.mount)
        assertEquals(nodeA, event.at)
        assertEquals(9L, event.tick)
        assertEquals(claimer, store.findById(mount.id)!!.ownerAgentId)
    }

    @Test
    fun `not in world is rejected with NotInWorld`() {
        val mount = mount(owner = null, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = emptyMap())

        val rejection = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = mount.id),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 0),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        assertIs<WorldRejection.NotInWorld>(rejection)
    }

    @Test
    fun `unknown mount id is rejected with UnknownMount`() {
        val store = InMemoryMountStore()
        val state = stateWith(positions = mapOf(claimer to nodeA))
        val unknown = MountId(UUID.randomUUID())

        val rejection = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = unknown),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 0),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.UnknownMount>(rejection)
        assertEquals(unknown, r.mount)
    }

    @Test
    fun `already owned mount is rejected with MountAlreadyOwned`() {
        val existingOwner = AgentId(UUID.randomUUID())
        val mount = mount(owner = existingOwner, at = nodeA)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(claimer to nodeA))

        val rejection = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = mount.id),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 0),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.MountAlreadyOwned>(rejection)
        assertEquals(existingOwner, r.owner)
        assertEquals(existingOwner, store.findById(mount.id)!!.ownerAgentId)
    }

    @Test
    fun `mount at different node is rejected with MountNotAtSameNode`() {
        val mount = mount(owner = null, at = nodeB)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(claimer to nodeA))

        val rejection = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = mount.id),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 0),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.MountNotAtSameNode>(rejection)
        assertEquals(nodeA, r.agentAt)
        assertEquals(nodeB, r.mountAt)
        assertNull(store.findById(mount.id)!!.ownerAgentId)
    }

    @Test
    fun `at-cap claimer is rejected with MountCapReached`() {
        val target = mount(owner = null, at = nodeA)
        val owned = mount(owner = claimer, at = nodeA)
        val store = InMemoryMountStore().apply { insert(target); insert(owned) }
        val state = stateWith(positions = mapOf(claimer to nodeA))

        val rejection = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = target.id),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 0),
            mounts = store,
            tick = 1L,
        ).leftOrNull()

        val r = assertIs<WorldRejection.MountCapReached>(rejection)
        assertEquals(1, r.cap)
        assertNull(store.findById(target.id)!!.ownerAgentId)
    }

    @Test
    fun `high ANIMAL_HANDLING raises the cap`() {
        val target = mount(owner = null, at = nodeA)
        val owned = mount(owner = claimer, at = nodeA)
        val store = InMemoryMountStore().apply { insert(target); insert(owned) }
        val state = stateWith(positions = mapOf(claimer to nodeA))

        val result = reduceClaimTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.ClaimTransport(agent = claimer, mount = target.id),
            balance = stubBalance(),
            skills = StubSkills(animalHandlingLevel = 50),
            mounts = store,
            tick = 1L,
        ).getOrNull()

        assertNotNull(result)
        assertEquals(claimer, store.findById(target.id)!!.ownerAgentId)
    }

    private fun stateWith(positions: Map<AgentId, NodeId>): WorldState =
        WorldState(positions = positions)

    private fun mount(owner: AgentId?, at: NodeId): Mount = Mount(
        id = MountId(UUID.randomUUID()),
        type = MountType("RIDING_HORSE"),
        ownerAgentId = owner,
        nodeId = at,
        hpCurrent = 50,
        hpMax = 50,
        hunger = 100,
        hungerMax = 100,
        fatigue = 100,
        fatigueMax = 100,
        mountedByAgentId = null,
        tamedAtTick = 0L,
    )

    private class StubSkills(private val animalHandlingLevel: Int) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int =
            if (skill == SkillId("ANIMAL_HANDLING")) animalHandlingLevel else 0
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }
}
