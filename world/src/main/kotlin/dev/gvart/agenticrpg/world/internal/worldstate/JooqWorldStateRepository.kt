package dev.gvart.agenticrpg.world.internal.worldstate

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.Biome
import dev.gvart.agenticrpg.world.Climate
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.RegionId
import dev.gvart.agenticrpg.world.Terrain
import dev.gvart.agenticrpg.world.Vec3
import dev.gvart.agenticrpg.world.WorldId
import dev.gvart.agenticrpg.world.internal.body.AgentBody
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.NODES
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.NODE_ADJACENCY
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.REGIONS
import dev.gvart.agenticrpg.world.internal.jooq.tables.references.REGION_NEIGHBORS
import jakarta.annotation.PostConstruct
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqWorldStateRepository(
    private val dsl: DSLContext,
    private val staticConfig: WorldStaticConfig,
) : WorldStateRepository {

    @PostConstruct
    fun init() {
        staticConfig.reload()
    }

    override fun load(): WorldState = WorldState(
        regions = staticConfig.regions,
        nodes = staticConfig.nodes,
        positions = loadActivePositions(),
        bodies = loadBodies(),
    )

    /**
     * Persists the world state at the end of a tick.
     *
     * Presence vs. character continuity:
     * - `agent_positions.active` is the **presence** flag — flipped to `false` on despawn (see
     *   [tombstoneMissing]) and back to `true` on the next spawn. Presence is ephemeral.
     * - `agent_bodies` rows are **character state** — HP, stamina, mana, and (later) skills,
     *   levels, and inventory. They persist across despawn/spawn so progress carries between
     *   sessions. The spawn reducer resumes from the persisted body if one exists.
     *
     * Body deletion is reserved for permanent character deletion (e.g. the 9-deaths permadeath
     * rule, future work) — it must not happen on a per-session despawn.
     */
    @Transactional
    override fun save(state: WorldState) {
        tombstoneMissing(state.positions.keys)
        state.positions.forEach { (agent, node) -> upsertActivePosition(agent, node) }
        state.bodies.forEach { (agent, body) -> upsertBody(agent, body) }
    }

    private fun loadActivePositions(): Map<AgentId, NodeId> =
        dsl.select(AGENT_POSITIONS.AGENT_ID, AGENT_POSITIONS.NODE_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.ACTIVE.isTrue)
            .fetch { AgentId(it[AGENT_POSITIONS.AGENT_ID]!!) to NodeId(it[AGENT_POSITIONS.NODE_ID]!!) }
            .toMap()

    private fun loadBodies(): Map<AgentId, AgentBody> =
        dsl.selectFrom(AGENT_BODIES)
            .fetch {
                AgentId(it[AGENT_BODIES.AGENT_ID]!!) to AgentBody(
                    hp = it[AGENT_BODIES.HP]!!,
                    maxHp = it[AGENT_BODIES.MAX_HP]!!,
                    stamina = it[AGENT_BODIES.STAMINA]!!,
                    maxStamina = it[AGENT_BODIES.MAX_STAMINA]!!,
                    mana = it[AGENT_BODIES.MANA]!!,
                    maxMana = it[AGENT_BODIES.MAX_MANA]!!,
                )
            }
            .toMap()

    /**
     * Marks any currently-active position whose agent is no longer in `liveAgents` as inactive
     * (presence tombstone). Note this only touches `agent_positions`; bodies are intentionally
     * left alone — see [save] for the rationale.
     */
    private fun tombstoneMissing(liveAgents: Set<AgentId>) {
        val update = dsl.update(AGENT_POSITIONS).set(AGENT_POSITIONS.ACTIVE, false)
        if (liveAgents.isEmpty()) {
            update.where(AGENT_POSITIONS.ACTIVE.isTrue).execute()
        } else {
            update
                .where(AGENT_POSITIONS.ACTIVE.isTrue)
                .and(AGENT_POSITIONS.AGENT_ID.notIn(liveAgents.map { it.id }))
                .execute()
        }
    }

    private fun upsertActivePosition(agent: AgentId, node: NodeId) {
        dsl.insertInto(AGENT_POSITIONS)
            .set(AGENT_POSITIONS.AGENT_ID, agent.id)
            .set(AGENT_POSITIONS.NODE_ID, node.value)
            .set(AGENT_POSITIONS.ACTIVE, true)
            .onConflict(AGENT_POSITIONS.AGENT_ID)
            .doUpdate()
            .set(AGENT_POSITIONS.NODE_ID, node.value)
            .set(AGENT_POSITIONS.ACTIVE, true)
            .execute()
    }

    private fun upsertBody(agent: AgentId, body: AgentBody) {
        dsl.insertInto(AGENT_BODIES)
            .set(AGENT_BODIES.AGENT_ID, agent.id)
            .set(AGENT_BODIES.HP, body.hp)
            .set(AGENT_BODIES.MAX_HP, body.maxHp)
            .set(AGENT_BODIES.STAMINA, body.stamina)
            .set(AGENT_BODIES.MAX_STAMINA, body.maxStamina)
            .set(AGENT_BODIES.MANA, body.mana)
            .set(AGENT_BODIES.MAX_MANA, body.maxMana)
            .onConflict(AGENT_BODIES.AGENT_ID)
            .doUpdate()
            .set(AGENT_BODIES.HP, body.hp)
            .set(AGENT_BODIES.MAX_HP, body.maxHp)
            .set(AGENT_BODIES.STAMINA, body.stamina)
            .set(AGENT_BODIES.MAX_STAMINA, body.maxStamina)
            .set(AGENT_BODIES.MANA, body.mana)
            .set(AGENT_BODIES.MAX_MANA, body.maxMana)
            .execute()
    }
}

/**
 * Holds the immutable graph of regions and nodes loaded from the database. Reused by the
 * repository (for full WorldState assembly) and the query gateway (for point lookups without an
 * extra query).
 *
 * Reloaded after editor mutations via [reload]; reads are atomic via @Volatile maps.
 */
@Component
internal class WorldStaticConfig(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) {
    @Volatile private var _regions: Map<RegionId, Region> = emptyMap()
    @Volatile private var _nodes: Map<NodeId, Node> = emptyMap()

    val regions: Map<RegionId, Region> get() = _regions
    val nodes: Map<NodeId, Node> get() = _nodes

    fun region(id: RegionId): Region? = _regions[id]
    fun node(id: NodeId): Node? = _nodes[id]

    @Synchronized
    fun reload() {
        val neighborsByRegion = dsl.select(REGION_NEIGHBORS.REGION_ID, REGION_NEIGHBORS.NEIGHBOR_ID)
            .from(REGION_NEIGHBORS)
            .fetch()
            .groupBy({ it[REGION_NEIGHBORS.REGION_ID]!! }, { RegionId(it[REGION_NEIGHBORS.NEIGHBOR_ID]!!) })

        _regions = dsl.select(
            REGIONS.ID, REGIONS.WORLD_ID, REGIONS.SPHERE_INDEX, REGIONS.BIOME, REGIONS.CLIMATE,
            REGIONS.CENTROID_X, REGIONS.CENTROID_Y, REGIONS.CENTROID_Z, REGIONS.FACE_VERTICES,
        )
            .from(REGIONS)
            .fetch()
            .associate { row ->
                val id = RegionId(row[REGIONS.ID]!!)
                id to Region(
                    id = id,
                    worldId = WorldId(row[REGIONS.WORLD_ID]!!),
                    sphereIndex = row[REGIONS.SPHERE_INDEX]!!,
                    biome = row[REGIONS.BIOME]?.let(Biome::valueOf),
                    climate = row[REGIONS.CLIMATE]?.let(Climate::valueOf),
                    centroid = Vec3(
                        row[REGIONS.CENTROID_X]!!,
                        row[REGIONS.CENTROID_Y]!!,
                        row[REGIONS.CENTROID_Z]!!,
                    ),
                    faceVertices = decodeVertices(row[REGIONS.FACE_VERTICES]!!),
                    neighbors = neighborsByRegion[id.value]?.toSet() ?: emptySet(),
                )
            }

        val adjacencyByNode = dsl.select(NODE_ADJACENCY.FROM_NODE_ID, NODE_ADJACENCY.TO_NODE_ID)
            .from(NODE_ADJACENCY)
            .fetch()
            .groupBy({ it[NODE_ADJACENCY.FROM_NODE_ID]!! }, { NodeId(it[NODE_ADJACENCY.TO_NODE_ID]!!) })

        _nodes = dsl.select(NODES.ID, NODES.REGION_ID, NODES.Q, NODES.R, NODES.TERRAIN)
            .from(NODES)
            .fetch()
            .associate { row ->
                val id = NodeId(row[NODES.ID]!!)
                id to Node(
                    id = id,
                    regionId = RegionId(row[NODES.REGION_ID]!!),
                    q = row[NODES.Q]!!,
                    r = row[NODES.R]!!,
                    terrain = Terrain.valueOf(row[NODES.TERRAIN]!!),
                    adjacency = adjacencyByNode[id.value]?.toSet() ?: emptySet(),
                )
            }
    }

    private fun decodeVertices(jsonb: JSON): List<Vec3> {
        val raw: List<List<Double>> = mapper.readValue(jsonb.data(), VERTICES_TYPE)
        return raw.map(Vec3.Companion::of)
    }

    private companion object {
        private val VERTICES_TYPE = object : TypeReference<List<List<Double>>>() {}
    }
}
