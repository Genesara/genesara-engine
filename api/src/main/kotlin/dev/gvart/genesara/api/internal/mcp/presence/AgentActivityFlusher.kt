package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentLastActiveStore
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
internal class AgentActivityFlusher(
    private val source: ActivitySnapshotSource,
    private val tracker: AgentActivityTracker,
    private val store: AgentLastActiveStore,
) {

    @Scheduled(fixedDelayString = "\${application.presence.flush-interval}")
    fun flush() {
        val snapshot = source.snapshot()
        if (snapshot.isEmpty()) return
        store.saveLastActive(snapshot)
        snapshot.keys.forEach(tracker::forget)
    }
}
