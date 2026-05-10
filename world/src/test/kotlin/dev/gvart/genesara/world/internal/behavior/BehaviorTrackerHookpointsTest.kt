package dev.gvart.genesara.world.internal.behavior

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BehaviorTrackerHookpointsTest {

    private val agent = AgentId(UUID.randomUUID())
    private val other = AgentId(UUID.randomUUID())

    @Test
    fun `record bumps the (agent, category) counter and stamps the tick`() {
        val tracker = InMemoryBehaviorTracker()

        tracker.record(agent, ActionCategory.COMBAT, tick = 3L)
        tracker.record(agent, ActionCategory.COMBAT, tick = 11L)

        assertEquals(mapOf(ActionCategory.COMBAT to 2), tracker.snapshotFor(agent))
        assertEquals(11L, tracker.lastTickFor(agent, ActionCategory.COMBAT))
    }

    @Test
    fun `snapshotFor isolates by agent`() {
        val tracker = InMemoryBehaviorTracker()
        tracker.record(agent, ActionCategory.COMBAT, tick = 1L)
        tracker.record(other, ActionCategory.GATHER, tick = 1L)

        assertEquals(mapOf(ActionCategory.COMBAT to 1), tracker.snapshotFor(agent))
        assertEquals(mapOf(ActionCategory.GATHER to 1), tracker.snapshotFor(other))
        assertNull(tracker.lastTickFor(agent, ActionCategory.GATHER))
    }
}
