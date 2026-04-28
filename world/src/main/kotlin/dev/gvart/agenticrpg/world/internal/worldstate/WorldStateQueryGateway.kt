package dev.gvart.agenticrpg.world.internal.worldstate

import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.RegionId
import dev.gvart.agenticrpg.world.WorldQueryGateway
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.AGENT_POSITIONS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Component

@Component
internal class WorldStateQueryGateway(
    private val dsl: DSLContext,
    private val staticConfig: WorldStaticConfig,
) : WorldQueryGateway {

    override fun locationOf(agent: AgentId): NodeId? =
        dsl.select(AGENT_POSITIONS.NODE_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agent.id))
            .fetchOne(AGENT_POSITIONS.NODE_ID)
            ?.let(::NodeId)

    override fun activePositionOf(agent: AgentId): NodeId? =
        dsl.select(AGENT_POSITIONS.NODE_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agent.id))
            .and(AGENT_POSITIONS.ACTIVE.isTrue)
            .fetchOne(AGENT_POSITIONS.NODE_ID)
            ?.let(::NodeId)

    override fun node(id: NodeId): Node? = staticConfig.node(id)

    override fun region(id: RegionId): Region? = staticConfig.region(id)

    override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> {
        if (radius < 0) return emptySet()
        if (staticConfig.node(origin) == null) return emptySet()
        return dsl.resultQuery(
            "SELECT node_id FROM fn_nodes_within({0}, {1})",
            DSL.value(origin.value, SQLDataType.BIGINT),
            DSL.value(radius, SQLDataType.INTEGER),
        ).fetch { (it.get(0) as Long).let(::NodeId) }
            .toSet()
    }

    override fun randomSpawnableNode(): NodeId? = staticConfig.nodes.keys.randomOrNull()
}
