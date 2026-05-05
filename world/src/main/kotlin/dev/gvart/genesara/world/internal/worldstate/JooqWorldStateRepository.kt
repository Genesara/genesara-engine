package dev.gvart.genesara.world.internal.worldstate

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_KILL_STREAKS
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_ADJACENCY
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.REGION_NEIGHBORS
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
        inventories = loadInventories(),
        killStreaks = loadKillStreaks(),
    )

    /**
     * Persists the world state at the end of a tick.
     *
     * Presence vs. character continuity:
     * - `agent_positions.active` is the **presence** flag — flipped to `false` on despawn (see
     *   [tombstoneMissing]) and back to `true` on the next spawn. Presence is ephemeral.
     * - `agent_bodies` rows are **character state** — HP, stamina, mana, and (later) skills,
     *   levels. They persist across despawn/spawn so progress carries between sessions. The
     *   spawn reducer resumes from the persisted body if one exists.
     * - `agent_inventory` rows are also character state. Same lifetime rule as bodies.
     *
     * **Persistence semantics — important asymmetry:**
     * Only [tombstoneMissing] uses the cross-agent "anything not in the map disappears"
     * semantics. The body/inventory loops are **per-agent additive only** — they upsert
     * for every agent in the state map and never delete rows for absent agents. That's
     * deliberate: an agent missing from `state.bodies` / `state.inventories` is in transit
     * (between load and save the reducer didn't touch them) or has logged out, not deleted.
     *
     * Per-agent within-row sync IS done by [saveInventory] (orphan-removal of dropped
     * `item_id`s for that one agent) — see its docstring.
     *
     * Permadeath / cross-agent deletion is reserved for an explicit future code path that
     * owns its own DELETE; it must not happen here on a per-session despawn.
     */
    @Transactional
    override fun save(state: WorldState) {
        tombstoneMissing(state.positions.keys)
        state.positions.forEach { (agent, node) -> upsertActivePosition(agent, node) }
        state.bodies.forEach { (agent, body) -> upsertBody(agent, body) }
        state.inventories.forEach { (agent, inventory) -> saveInventory(agent, inventory) }
        state.killStreaks.forEach { (agent, streak) -> saveKillStreak(agent, streak) }
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
                    hunger = it[AGENT_BODIES.HUNGER]!!,
                    maxHunger = it[AGENT_BODIES.MAX_HUNGER]!!,
                    thirst = it[AGENT_BODIES.THIRST]!!,
                    maxThirst = it[AGENT_BODIES.MAX_THIRST]!!,
                    sleep = it[AGENT_BODIES.SLEEP]!!,
                    maxSleep = it[AGENT_BODIES.MAX_SLEEP]!!,
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
            .set(AGENT_BODIES.HUNGER, body.hunger)
            .set(AGENT_BODIES.MAX_HUNGER, body.maxHunger)
            .set(AGENT_BODIES.THIRST, body.thirst)
            .set(AGENT_BODIES.MAX_THIRST, body.maxThirst)
            .set(AGENT_BODIES.SLEEP, body.sleep)
            .set(AGENT_BODIES.MAX_SLEEP, body.maxSleep)
            .onConflict(AGENT_BODIES.AGENT_ID)
            .doUpdate()
            .set(AGENT_BODIES.HP, body.hp)
            .set(AGENT_BODIES.MAX_HP, body.maxHp)
            .set(AGENT_BODIES.STAMINA, body.stamina)
            .set(AGENT_BODIES.MAX_STAMINA, body.maxStamina)
            .set(AGENT_BODIES.MANA, body.mana)
            .set(AGENT_BODIES.MAX_MANA, body.maxMana)
            .set(AGENT_BODIES.HUNGER, body.hunger)
            .set(AGENT_BODIES.MAX_HUNGER, body.maxHunger)
            .set(AGENT_BODIES.THIRST, body.thirst)
            .set(AGENT_BODIES.MAX_THIRST, body.maxThirst)
            .set(AGENT_BODIES.SLEEP, body.sleep)
            .set(AGENT_BODIES.MAX_SLEEP, body.maxSleep)
            .execute()
    }

    private fun loadInventories(): Map<AgentId, AgentInventory> =
        dsl.select(AGENT_INVENTORY.AGENT_ID, AGENT_INVENTORY.ITEM_ID, AGENT_INVENTORY.QUANTITY)
            .from(AGENT_INVENTORY)
            .fetch()
            .groupBy({ AgentId(it[AGENT_INVENTORY.AGENT_ID]!!) }) {
                ItemId(it[AGENT_INVENTORY.ITEM_ID]!!) to it[AGENT_INVENTORY.QUANTITY]!!
            }
            .mapValues { (_, pairs) -> AgentInventory(pairs.toMap()) }

    /**
     * Upserts every stack and removes rows for items the agent no longer holds. Per-agent
     * full sync is fine — inventories are small (tens of stacks) and the reducer always
     * produces a complete picture for the agents it touched.
     */
    private fun saveInventory(agent: AgentId, inventory: AgentInventory) {
        val keep = inventory.stacks.keys.map { it.value }
        if (keep.isEmpty()) {
            dsl.deleteFrom(AGENT_INVENTORY)
                .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
                .execute()
        } else {
            dsl.deleteFrom(AGENT_INVENTORY)
                .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
                .and(AGENT_INVENTORY.ITEM_ID.notIn(keep))
                .execute()
        }
        inventory.stacks.forEach { (item, qty) ->
            dsl.insertInto(AGENT_INVENTORY)
                .set(AGENT_INVENTORY.AGENT_ID, agent.id)
                .set(AGENT_INVENTORY.ITEM_ID, item.value)
                .set(AGENT_INVENTORY.QUANTITY, qty)
                .onConflict(AGENT_INVENTORY.AGENT_ID, AGENT_INVENTORY.ITEM_ID)
                .doUpdate()
                .set(AGENT_INVENTORY.QUANTITY, qty)
                .execute()
        }
    }

    private fun loadKillStreaks(): Map<AgentId, AgentKillStreak> =
        dsl.select(
            AGENT_KILL_STREAKS.AGENT_ID,
            AGENT_KILL_STREAKS.KILL_COUNT,
            AGENT_KILL_STREAKS.WINDOW_START_TICK,
        )
            .from(AGENT_KILL_STREAKS)
            .fetch {
                AgentId(it[AGENT_KILL_STREAKS.AGENT_ID]!!) to AgentKillStreak(
                    killCount = it[AGENT_KILL_STREAKS.KILL_COUNT]!!,
                    windowStartTick = it[AGENT_KILL_STREAKS.WINDOW_START_TICK]!!,
                )
            }
            .toMap()

    /**
     * `AgentKillStreak.EMPTY` is the absence of a streak — delete the row rather
     * than persist a (0, 0) sentinel. The next read reconstructs `EMPTY` via
     * `WorldState.killStreakOf` so deleted vs (0, 0) is observationally identical
     * but the table stays compact.
     */
    private fun saveKillStreak(agent: AgentId, streak: AgentKillStreak) {
        if (streak == AgentKillStreak.EMPTY) {
            dsl.deleteFrom(AGENT_KILL_STREAKS)
                .where(AGENT_KILL_STREAKS.AGENT_ID.eq(agent.id))
                .execute()
            return
        }
        dsl.insertInto(AGENT_KILL_STREAKS)
            .set(AGENT_KILL_STREAKS.AGENT_ID, agent.id)
            .set(AGENT_KILL_STREAKS.KILL_COUNT, streak.killCount)
            .set(AGENT_KILL_STREAKS.WINDOW_START_TICK, streak.windowStartTick)
            .onConflict(AGENT_KILL_STREAKS.AGENT_ID)
            .doUpdate()
            .set(AGENT_KILL_STREAKS.KILL_COUNT, streak.killCount)
            .set(AGENT_KILL_STREAKS.WINDOW_START_TICK, streak.windowStartTick)
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

        _nodes = dsl.select(NODES.ID, NODES.REGION_ID, NODES.Q, NODES.R, NODES.TERRAIN, NODES.PVP_ENABLED)
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
                    pvpEnabled = row[NODES.PVP_ENABLED] ?: true,
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
