package dev.gvart.genesara.world.economy.internal.trade

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.world.RelationshipLookup
import org.springframework.stereotype.Component

@Component
class RelationshipsGatewayLookup(
    private val gateway: RelationshipsGateway,
) : RelationshipLookup {
    override fun scoreBetween(a: AgentId, b: AgentId): Int =
        if (a == b) 0 else gateway.find(a, b)?.score ?: 0
}
