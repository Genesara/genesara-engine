package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
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

class MountTransportReducersTest {

    private val rider = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val node = NodeId(1L)
    private val elsewhere = NodeId(2L)

    @Test
    fun `mount happy path — sets rider, emits TransportMounted with owner routed`() {
        val mount = mount(at = node, owner = rider)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(rider to node))

        val result = reduceMountTransport(
            environment = state.environment,
            core = state.core,
            command = EnvironmentCommand.MountTransport(agent = rider, mount = mount.id),
            mounts = store,
            tick = 5L,
        )

        val out = result.getOrNull() ?: error("expected success, got $result")
        val event = out.events.filterIsInstance<EnvironmentEvent.TransportMounted>().single()
        assertEquals(rider, event.agent)
        assertEquals(mount.id, event.mount)
        assertEquals(rider, event.owner, "owner is routed for notification")
        assertEquals(rider, store.findById(mount.id)!!.mountedByAgentId)
    }

    @Test
    fun `mount rejects when mount is at a different node`() {
        val mount = mount(at = elsewhere, owner = rider)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(rider to node))

        val r = reduceMountTransport(
            environment = state.environment, core = state.core,
            command = EnvironmentCommand.MountTransport(agent = rider, mount = mount.id),
            mounts = store, tick = 5L,
        ).leftOrNull()

        assertIs<WorldRejection.MountNotAtSameNode>(r)
    }

    @Test
    fun `mount rejects when mount already ridden by another agent`() {
        val mount = mount(at = node, owner = rider, mountedBy = otherAgent)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(rider to node))

        val r = reduceMountTransport(
            environment = state.environment, core = state.core,
            command = EnvironmentCommand.MountTransport(agent = rider, mount = mount.id),
            mounts = store, tick = 5L,
        ).leftOrNull()

        val mam = assertIs<WorldRejection.MountAlreadyMounted>(r)
        assertEquals(otherAgent, mam.rider)
    }

    @Test
    fun `mount rejects when agent is already riding another mount`() {
        val first = mount(at = node, owner = rider, mountedBy = rider)
        val second = mount(at = node, owner = rider)
        val store = InMemoryMountStore().apply { insert(first); insert(second) }
        val state = stateWith(positions = mapOf(rider to node))

        val r = reduceMountTransport(
            environment = state.environment, core = state.core,
            command = EnvironmentCommand.MountTransport(agent = rider, mount = second.id),
            mounts = store, tick = 5L,
        ).leftOrNull()

        val already = assertIs<WorldRejection.AlreadyMounted>(r)
        assertEquals(first.id, already.currentMount)
    }

    @Test
    fun `mount rejects when target is dead`() {
        val mount = mount(at = node, owner = rider, hpCurrent = 0)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(rider to node))

        val r = reduceMountTransport(
            environment = state.environment, core = state.core,
            command = EnvironmentCommand.MountTransport(agent = rider, mount = mount.id),
            mounts = store, tick = 5L,
        ).leftOrNull()

        assertIs<WorldRejection.MountAlreadyDead>(r)
    }

    @Test
    fun `dismount happy path — clears rider, emits TransportDismounted with command causedBy`() {
        val mount = mount(at = node, owner = rider, mountedBy = rider)
        val store = InMemoryMountStore().apply { insert(mount) }
        val state = stateWith(positions = mapOf(rider to node))
        val command = EnvironmentCommand.DismountTransport(agent = rider)

        val out = reduceDismountTransport(
            environment = state.environment, core = state.core,
            command = command, mounts = store, tick = 5L,
        ).getOrNull() ?: error("expected success")

        val event = out.events.filterIsInstance<EnvironmentEvent.TransportDismounted>().single()
        assertEquals(rider, event.agent)
        assertEquals(mount.id, event.mount)
        assertEquals(command.commandId, event.causedBy)
        assertNull(store.findById(mount.id)!!.mountedByAgentId)
    }

    @Test
    fun `dismount rejects when not mounted`() {
        val state = stateWith(positions = mapOf(rider to node))
        val r = reduceDismountTransport(
            environment = state.environment, core = state.core,
            command = EnvironmentCommand.DismountTransport(agent = rider),
            mounts = InMemoryMountStore(), tick = 5L,
        ).leftOrNull()

        assertIs<WorldRejection.NotMounted>(r)
    }

    private fun stateWith(positions: Map<AgentId, NodeId>): WorldState =
        WorldState(positions = positions)

    private fun mount(
        at: NodeId,
        owner: AgentId?,
        mountedBy: AgentId? = null,
        hpCurrent: Int = 50,
    ): Mount = Mount(
        id = MountId(UUID.randomUUID()),
        type = MountType("RIDING_HORSE"),
        ownerAgentId = owner,
        nodeId = at,
        hpCurrent = hpCurrent,
        hpMax = 50,
        hunger = 100, hungerMax = 100,
        fatigue = 100, fatigueMax = 100,
        mountedByAgentId = mountedBy,
        tamedAtTick = 0L,
    )
}
