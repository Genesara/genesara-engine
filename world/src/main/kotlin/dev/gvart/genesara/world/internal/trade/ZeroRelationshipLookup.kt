package dev.gvart.genesara.world.internal.trade

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.RelationshipLookup
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

// TODO(#14): Replace with the jOOQ-backed RelationshipsGateway from the Phase 2
// Relationships slice. The trust gate in TradeReducer reads through this
// interface; swapping the bean is the only wiring change.
@Component
internal class ZeroRelationshipLookup : RelationshipLookup {
    override fun scoreBetween(a: AgentId, b: AgentId): Int = 0
}
