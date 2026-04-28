package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Component

@Component
internal class WorldStateQueryGateway(
    private val dsl: DSLContext,
    private val staticConfig: WorldStaticConfig,
    private val starterNodes: StarterNodeLookup,
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

    override fun starterNodeFor(race: RaceId): NodeId? = starterNodes.byRace(race)

    override fun bodyOf(agent: AgentId): BodyView? =
        dsl.select(
            AGENT_BODIES.HP, AGENT_BODIES.MAX_HP,
            AGENT_BODIES.STAMINA, AGENT_BODIES.MAX_STAMINA,
            AGENT_BODIES.MANA, AGENT_BODIES.MAX_MANA,
            AGENT_BODIES.HUNGER, AGENT_BODIES.MAX_HUNGER,
            AGENT_BODIES.THIRST, AGENT_BODIES.MAX_THIRST,
            AGENT_BODIES.SLEEP, AGENT_BODIES.MAX_SLEEP,
        )
            .from(AGENT_BODIES)
            .where(AGENT_BODIES.AGENT_ID.eq(agent.id))
            .fetchOne()
            ?.let {
                BodyView(
                    hp = it[AGENT_BODIES.HP]!!,
                    maxHp = it[AGENT_BODIES.MAX_HP]!!,
                    stamina = it[AGENT_BODIES.STAMINA]!!,
                    maxStamina = it[AGENT_BODIES.MAX_STAMINA]!!,
                    mana = it[AGENT_BODIES.MANA]!!,
                    maxMana = it[AGENT_BODIES.MAX_MANA]!!,
                    hunger = it[AGENT_BODIES.HUNGER]!!,
                    maxHunger = it[AGENT_BODIES.MAX_HUNGER]!!,
                    thirst = it[AGENT_BODIES.THIRST]!!,
                    maxThirst = it[AGENT_BODIES.MAX_THIRST]!!,
                    sleep = it[AGENT_BODIES.SLEEP]!!,
                    maxSleep = it[AGENT_BODIES.MAX_SLEEP]!!,
                )
            }

    override fun inventoryOf(agent: AgentId): InventoryView {
        val entries = dsl.select(AGENT_INVENTORY.ITEM_ID, AGENT_INVENTORY.QUANTITY)
            .from(AGENT_INVENTORY)
            .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
            .orderBy(AGENT_INVENTORY.ITEM_ID.asc())
            .fetch {
                InventoryEntry(
                    itemId = ItemId(it[AGENT_INVENTORY.ITEM_ID]!!),
                    quantity = it[AGENT_INVENTORY.QUANTITY]!!,
                )
            }
        return InventoryView(entries)
    }
}
