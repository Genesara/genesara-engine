package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.NodeId
import java.util.UUID

/**
 * No-op [GroundItemStore] for integration tests that need a `WorldStateQueryGateway`
 * or `WorldTickHandler` but don't exercise the drop / pickup flow. Returns empty
 * for reads; throws on writes so accidental traffic surfaces loudly.
 */
internal object NoOpGroundItemStore : GroundItemStore {
    override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) =
        error("NoOpGroundItemStore: deposit should not be called in this test")
    override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
    override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
}
