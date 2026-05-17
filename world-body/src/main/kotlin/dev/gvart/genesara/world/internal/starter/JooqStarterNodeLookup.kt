package dev.gvart.genesara.world.internal.starter

import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.STARTER_NODES
import org.jooq.DSLContext
import org.springframework.stereotype.Component

@Component
internal class JooqStarterNodeLookup(
    private val dsl: DSLContext,
) : StarterNodeLookup {

    override fun byRace(race: RaceId): NodeId? =
        dsl.select(STARTER_NODES.NODE_ID)
            .from(STARTER_NODES)
            .where(STARTER_NODES.RACE_ID.eq(race.value))
            .fetchOne(STARTER_NODES.NODE_ID)
            ?.let(::NodeId)
}
