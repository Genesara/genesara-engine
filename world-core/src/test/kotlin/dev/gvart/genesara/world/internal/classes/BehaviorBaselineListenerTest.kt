package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class BehaviorBaselineListenerTest {

    @Test
    fun `marks the baseline so post-classing window starts at zero`() {
        val tracker = InMemoryBehaviorTracker()
        val agentId = AgentId(UUID.randomUUID())
        repeat(10) { _ -> tracker.record(agentId, ActionCategory.COMBAT, tick = 1L) }
        repeat(3) { _ -> tracker.record(agentId, ActionCategory.GATHER, tick = 1L) }
        val listener = BehaviorBaselineListener(tracker)

        listener.on(AgentEvent.ClassChosen(agent = agentId, classId = AgentClass.SOLDIER, tick = 5L))

        // Cumulative read still sees the pre-classing counters.
        assertEquals(mapOf(ActionCategory.COMBAT to 10, ActionCategory.GATHER to 3), tracker.snapshotFor(agentId))
        // Windowed read returns nothing right after baseline.
        assertEquals(emptyMap<ActionCategory, Int>(), tracker.snapshotForWindow(agentId))

        // Post-classing actions accrue into the windowed read; baseline stays put.
        repeat(4) { _ -> tracker.record(agentId, ActionCategory.COMBAT, tick = 6L) }
        repeat(2) { _ -> tracker.record(agentId, ActionCategory.EXPLORE, tick = 7L) }
        assertEquals(
            mapOf(ActionCategory.COMBAT to 4, ActionCategory.EXPLORE to 2),
            tracker.snapshotForWindow(agentId),
        )
    }
}
