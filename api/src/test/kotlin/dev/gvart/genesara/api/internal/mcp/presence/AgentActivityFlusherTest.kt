package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentLastActiveStore
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentActivityFlusherTest {

    private val now = Instant.parse("2026-05-02T12:00:00Z")
    private val agentA = AgentId(UUID.randomUUID())
    private val agentB = AgentId(UUID.randomUUID())

    @Test
    fun `flush copies snapshot to store and drains the tracker for those keys`() {
        val store = RecordingStore()
        val tracker = TrackingTracker()
        val source = ActivitySnapshotSource { mapOf(agentA to now, agentB to now.minusSeconds(30)) }
        val flusher = AgentActivityFlusher(source, tracker, store)

        flusher.flush()

        assertEquals(1, store.calls.size)
        assertEquals(now, store.calls.single()[agentA])
        assertEquals(now.minusSeconds(30), store.calls.single()[agentB])
        assertEquals(setOf(agentA, agentB), tracker.forgotten.toSet())
    }

    @Test
    fun `flush is a no-op when the snapshot is empty`() {
        val store = RecordingStore()
        val tracker = TrackingTracker()
        val flusher = AgentActivityFlusher(ActivitySnapshotSource { emptyMap() }, tracker, store)

        flusher.flush()

        assertTrue(store.calls.isEmpty())
        assertTrue(tracker.forgotten.isEmpty())
    }

    private class RecordingStore : AgentLastActiveStore {
        val calls = mutableListOf<Map<AgentId, Instant>>()
        override fun findLastActive(agentId: AgentId): Instant? = null
        override fun findLastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> = emptyMap()
        override fun saveLastActive(updates: Map<AgentId, Instant>) {
            calls += updates
        }
    }

    private class TrackingTracker : AgentActivityTracker {
        val forgotten = mutableListOf<AgentId>()
        override fun touch(agent: AgentId) {}
        override fun staleAgents(olderThan: Instant): List<AgentId> = emptyList()
        override fun lastActiveAt(agent: AgentId): Instant? = null
        override fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> = emptyMap()
        override fun forget(agent: AgentId) {
            forgotten += agent
        }
    }
}
