package dev.gvart.genesara.world.internal.editor

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.HexUpsert
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionGeometry
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.StarterNodeAssignment
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.World
import dev.gvart.genesara.world.WorldEditingError
import dev.gvart.genesara.world.WorldEditingGateway
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_ADJACENCY
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.REGION_NEIGHBORS
import dev.gvart.genesara.world.internal.jooq.tables.references.STARTER_NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.mesh.GoldbergFace
import dev.gvart.genesara.world.internal.mesh.GoldbergMeshGenerator
import dev.gvart.genesara.world.internal.mesh.faceCountForFrequency
import dev.gvart.genesara.world.internal.mesh.frequencyForNodeCount
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.resources.ResourceSpawner
import dev.gvart.genesara.world.internal.worldstate.WorldStaticConfig
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqWorldEditingGateway(
    private val dsl: DSLContext,
    private val mesh: GoldbergMeshGenerator,
    private val hexes: HexGridGenerator,
    private val biomeAssigner: BiomeAssigner,
    private val staticConfig: WorldStaticConfig,
    private val mapper: ObjectMapper,
    private val resourceSpawner: ResourceSpawner,
    private val resourceStore: NodeResourceStore,
    private val tickClock: TickClock,
    private val races: RaceLookup,
    private val balance: BalanceLookup,
) : WorldEditingGateway {

    override fun listWorlds(): List<World> =
        dsl.select(WORLDS.ID, WORLDS.NAME, WORLDS.NODE_COUNT, WORLDS.NODE_SIZE, WORLDS.FREQUENCY, WORLDS.CREATED_AT)
            .from(WORLDS)
            .orderBy(WORLDS.ID.asc())
            .fetch()
            .map(::toWorld)

    override fun getWorld(id: WorldId): World? =
        dsl.select(WORLDS.ID, WORLDS.NAME, WORLDS.NODE_COUNT, WORLDS.NODE_SIZE, WORLDS.FREQUENCY, WORLDS.CREATED_AT)
            .from(WORLDS)
            .where(WORLDS.ID.eq(id.value))
            .fetchOne()
            ?.let(::toWorld)

    @Transactional
    override fun createWorld(name: String, requestedNodeCount: Int, nodeSize: Int): World {
        val frequency = frequencyForNodeCount(requestedNodeCount)
        val actualCount = faceCountForFrequency(frequency)
        val generated = mesh.generateForFrequency(frequency)

        val worldId = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, actualCount)
            .set(WORLDS.NODE_SIZE, nodeSize)
            .set(WORLDS.FREQUENCY, frequency)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

        val regionIdBySphereIndex = insertRegions(worldId, generated.faces)
        val adjacency = wireSymmetricRegionNeighbors(generated.faces, regionIdBySphereIndex)
        paintInitialBiomesAndClimates(adjacency, worldId)

        staticConfig.reload()

        return getWorld(WorldId(worldId)) ?: error("just-inserted world disappeared: $worldId")
    }

    private fun insertRegions(worldId: Long, faces: List<GoldbergFace>): Map<Int, Long> {
        val byIndex = HashMap<Int, Long>(faces.size)
        for (face in faces) {
            val regionId = dsl.insertInto(REGIONS)
                .set(REGIONS.WORLD_ID, worldId)
                .set(REGIONS.SPHERE_INDEX, face.index)
                .set(REGIONS.BIOME, null as String?)
                .set(REGIONS.CLIMATE, null as String?)
                .set(REGIONS.CENTROID_X, face.centroid.x)
                .set(REGIONS.CENTROID_Y, face.centroid.y)
                .set(REGIONS.CENTROID_Z, face.centroid.z)
                .set(REGIONS.FACE_VERTICES, encodeVertices(face.vertices))
                .returningResult(REGIONS.ID)
                .fetchOne()!!.value1()!!
            byIndex[face.index] = regionId
        }
        return byIndex
    }

    private fun wireSymmetricRegionNeighbors(
        faces: List<GoldbergFace>,
        regionIdBySphereIndex: Map<Int, Long>,
    ): Map<Long, MutableList<Long>> {
        val adjacency = HashMap<Long, MutableList<Long>>(faces.size)
        for (face in faces) {
            val from = regionIdBySphereIndex[face.index]!!
            adjacency.getOrPut(from) { ArrayList(face.neighbors.size) }
            for (n in face.neighbors) {
                if (n < 0) continue
                val to = regionIdBySphereIndex[n] ?: continue
                adjacency.getValue(from) += to
                dsl.insertInto(REGION_NEIGHBORS)
                    .set(REGION_NEIGHBORS.REGION_ID, from)
                    .set(REGION_NEIGHBORS.NEIGHBOR_ID, to)
                    .onConflictDoNothing()
                    .execute()
            }
        }
        return adjacency
    }

    /**
     * Phase 0 finishing: every region must be playable immediately after creation, so we
     * assign a biome + climate at world-create time rather than waiting on an admin pass.
     * Roughly 30% of regions are flood-filled into contiguous OCEAN; land touching ocean
     * is forced to COASTAL. The PATCH endpoint can still override post-creation.
     */
    private fun paintInitialBiomesAndClimates(adjacency: Map<Long, MutableList<Long>>, worldSeed: Long) {
        val assignments = biomeAssigner.assign(adjacency = adjacency, worldSeed = worldSeed)
        for ((regionId, assignment) in assignments) {
            dsl.update(REGIONS)
                .set(REGIONS.BIOME, assignment.biome.name)
                .set(REGIONS.CLIMATE, assignment.climate.name)
                .where(REGIONS.ID.eq(regionId))
                .execute()
        }
    }

    override fun listRegions(worldId: WorldId): List<Region> {
        if (!worldExists(worldId)) throw WorldEditingError.WorldNotFound(worldId)
        val regions = dsl.select(
            REGIONS.ID, REGIONS.WORLD_ID, REGIONS.SPHERE_INDEX, REGIONS.BIOME, REGIONS.CLIMATE,
            REGIONS.CENTROID_X, REGIONS.CENTROID_Y, REGIONS.CENTROID_Z, REGIONS.FACE_VERTICES,
        )
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(worldId.value))
            .orderBy(REGIONS.SPHERE_INDEX.asc())
            .fetch()
        val neighborsByRegion = loadNeighbors(worldId)
        return regions.map { row ->
            val id = RegionId(row[REGIONS.ID]!!)
            toRegion(row, id, neighborsByRegion[id.value].orEmpty())
        }
    }

    override fun getRegion(worldId: WorldId, sphereIndex: Int): Region? {
        val row = dsl.select(
            REGIONS.ID, REGIONS.WORLD_ID, REGIONS.SPHERE_INDEX, REGIONS.BIOME, REGIONS.CLIMATE,
            REGIONS.CENTROID_X, REGIONS.CENTROID_Y, REGIONS.CENTROID_Z, REGIONS.FACE_VERTICES,
        )
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(worldId.value).and(REGIONS.SPHERE_INDEX.eq(sphereIndex)))
            .fetchOne() ?: return null
        val id = RegionId(row[REGIONS.ID]!!)
        val neighbors = dsl.select(REGION_NEIGHBORS.NEIGHBOR_ID)
            .from(REGION_NEIGHBORS)
            .where(REGION_NEIGHBORS.REGION_ID.eq(id.value))
            .fetch { RegionId(it[REGION_NEIGHBORS.NEIGHBOR_ID]!!) }
            .toSet()
        return toRegion(row, id, neighbors)
    }

    @Transactional
    override fun upsertRegionBiome(
        worldId: WorldId,
        sphereIndex: Int,
        biome: Biome,
        climate: Climate,
        geometry: RegionGeometry?,
    ): Region {
        if (!worldExists(worldId)) throw WorldEditingError.WorldNotFound(worldId)
        val existing = getRegion(worldId, sphereIndex)
        if (existing != null) {
            dsl.update(REGIONS)
                .set(REGIONS.BIOME, biome.name)
                .set(REGIONS.CLIMATE, climate.name)
                .where(REGIONS.ID.eq(existing.id.value))
                .execute()
        } else {
            val g = geometry ?: throw WorldEditingError.GeometryRequired()
            val newRegionId = dsl.insertInto(REGIONS)
                .set(REGIONS.WORLD_ID, worldId.value)
                .set(REGIONS.SPHERE_INDEX, sphereIndex)
                .set(REGIONS.BIOME, biome.name)
                .set(REGIONS.CLIMATE, climate.name)
                .set(REGIONS.CENTROID_X, g.centroid.x)
                .set(REGIONS.CENTROID_Y, g.centroid.y)
                .set(REGIONS.CENTROID_Z, g.centroid.z)
                .set(REGIONS.FACE_VERTICES, encodeVertices(g.faceVertices))
                .returningResult(REGIONS.ID)
                .fetchOne()!!.value1()!!
            linkToExistingNeighbors(worldId, newRegionId, g.neighborSphereIndices)
        }
        staticConfig.reload()
        return getRegion(worldId, sphereIndex)
            ?: error("region disappeared after upsert: world=${worldId.value} sphere=$sphereIndex")
    }

    private fun linkToExistingNeighbors(worldId: WorldId, newRegionId: Long, neighborSphereIndices: List<Int>) {
        val neighborIds = dsl.select(REGIONS.SPHERE_INDEX, REGIONS.ID)
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(worldId.value).and(REGIONS.SPHERE_INDEX.`in`(neighborSphereIndices)))
            .fetch { it[REGIONS.SPHERE_INDEX]!! to it[REGIONS.ID]!! }
            .toMap()
        for ((_, neighborId) in neighborIds) {
            dsl.insertInto(REGION_NEIGHBORS)
                .set(REGION_NEIGHBORS.REGION_ID, newRegionId)
                .set(REGION_NEIGHBORS.NEIGHBOR_ID, neighborId)
                .onConflictDoNothing()
                .execute()
            dsl.insertInto(REGION_NEIGHBORS)
                .set(REGION_NEIGHBORS.REGION_ID, neighborId)
                .set(REGION_NEIGHBORS.NEIGHBOR_ID, newRegionId)
                .onConflictDoNothing()
                .execute()
        }
    }

    @Transactional
    override fun patchRegion(
        worldId: WorldId,
        sphereIndex: Int,
        biome: MaybeSet<Biome?>,
        climate: MaybeSet<Climate?>,
    ): Region {
        if (!worldExists(worldId)) throw WorldEditingError.WorldNotFound(worldId)
        val existing = getRegion(worldId, sphereIndex)
            ?: throw WorldEditingError.RegionNotFound(worldId, sphereIndex)

        val touchesBiome = biome is MaybeSet.Set
        val touchesClimate = climate is MaybeSet.Set
        if (!touchesBiome && !touchesClimate) return existing

        val nextBiome = if (biome is MaybeSet.Set) biome.value else existing.biome
        val nextClimate = if (climate is MaybeSet.Set) climate.value else existing.climate

        dsl.update(REGIONS)
            .set(REGIONS.BIOME, nextBiome?.name)
            .set(REGIONS.CLIMATE, nextClimate?.name)
            .where(REGIONS.ID.eq(existing.id.value))
            .execute()
        staticConfig.reload()
        return getRegion(worldId, sphereIndex)
            ?: error("region disappeared after patch: world=${worldId.value} sphere=$sphereIndex")
    }

    override fun getOrSeedHexes(worldId: WorldId, sphereIndex: Int, radius: Int): List<Node> {
        val region = getRegion(worldId, sphereIndex)
            ?: throw WorldEditingError.RegionNotFound(worldId, sphereIndex)
        val existing = loadNodesFor(region.id)
        if (existing.isNotEmpty()) return existing
        return seedHexes(worldId, region, radius)
    }

    /**
     * Idempotent under concurrent calls — React StrictMode and double-fired effects in dev
     * hit this twice in parallel. `ON CONFLICT DO NOTHING` on tile + adjacency inserts means
     * the losing call observes the winner's data on re-read.
     */
    @Transactional
    internal fun seedHexes(worldId: WorldId, region: Region, radius: Int): List<Node> {
        val tiles = hexes.generate(
            worldId = worldId.value,
            sphereIndex = region.sphereIndex,
            radius = radius,
            biomeHint = region.biome?.representativeTerrain(),
            paintUniform = region.biome?.paintsUniformly() == true,
        )
        upsertTiles(region.id, tiles)
        val idByCoord = loadNodeIdsByCoord(region.id)
        wireHexAdjacency(idByCoord)
        staticConfig.reload()
        val nodes = loadNodesFor(region.id)
        seedResources(nodes, worldSeed = worldId.value)
        return nodes
    }

    private fun upsertTiles(regionId: RegionId, tiles: List<HexTile>) {
        for (tile in tiles) {
            dsl.insertInto(NODES)
                .set(NODES.REGION_ID, regionId.value)
                .set(NODES.Q, tile.q)
                .set(NODES.R, tile.r)
                .set(NODES.TERRAIN, tile.terrain.name)
                .onConflictDoNothing()
                .execute()
        }
    }

    private fun loadNodeIdsByCoord(regionId: RegionId): Map<Pair<Int, Int>, Long> =
        dsl.select(NODES.ID, NODES.Q, NODES.R)
            .from(NODES)
            .where(NODES.REGION_ID.eq(regionId.value))
            .fetch { (it[NODES.Q]!! to it[NODES.R]!!) to it[NODES.ID]!! }
            .toMap()

    private fun wireHexAdjacency(idByCoord: Map<Pair<Int, Int>, Long>) {
        for ((coord, fromId) in idByCoord) {
            val (q, r) = coord
            for ((dq, dr) in HEX_NEIGHBOR_OFFSETS) {
                val toId = idByCoord[(q + dq) to (r + dr)] ?: continue
                dsl.insertInto(NODE_ADJACENCY)
                    .set(NODE_ADJACENCY.FROM_NODE_ID, fromId)
                    .set(NODE_ADJACENCY.TO_NODE_ID, toId)
                    .onConflictDoNothing()
                    .execute()
            }
        }
    }

    @Transactional
    override fun mergeHexes(worldId: WorldId, sphereIndex: Int, tiles: List<HexUpsert>): Int {
        val region = getRegion(worldId, sphereIndex)
            ?: throw WorldEditingError.RegionNotFound(worldId, sphereIndex)
        for (tile in tiles) {
            repaintOrInsertTile(region.id, tile)
        }
        staticConfig.reload()
        seedResources(loadNodesFor(region.id), worldSeed = worldId.value)
        return tiles.size
    }

    /**
     * Re-paint preserves resource rows on the tile — wiping live state on every admin tweak
     * would be a foot-gun. A real re-spawn gets its own explicit endpoint.
     */
    private fun repaintOrInsertTile(regionId: RegionId, tile: HexUpsert) {
        val priorId = dsl.select(NODES.ID)
            .from(NODES)
            .where(NODES.REGION_ID.eq(regionId.value).and(NODES.Q.eq(tile.q)).and(NODES.R.eq(tile.r)))
            .fetchOne()
            ?.value1()
        if (priorId != null) {
            dsl.update(NODES)
                .set(NODES.TERRAIN, tile.terrain.name)
                .where(NODES.ID.eq(priorId))
                .execute()
        } else {
            dsl.insertInto(NODES)
                .set(NODES.REGION_ID, regionId.value)
                .set(NODES.Q, tile.q)
                .set(NODES.R, tile.r)
                .set(NODES.TERRAIN, tile.terrain.name)
                .execute()
        }
    }

    @Transactional(readOnly = true)
    override fun listStarterNodes(worldId: WorldId): List<StarterNodeAssignment> {
        if (!worldExists(worldId)) throw WorldEditingError.WorldNotFound(worldId)
        return dsl.select(STARTER_NODES.RACE_ID, STARTER_NODES.NODE_ID)
            .from(STARTER_NODES)
            .join(NODES).on(NODES.ID.eq(STARTER_NODES.NODE_ID))
            .join(REGIONS).on(REGIONS.ID.eq(NODES.REGION_ID))
            .where(REGIONS.WORLD_ID.eq(worldId.value))
            .orderBy(STARTER_NODES.RACE_ID.asc())
            .fetch {
                StarterNodeAssignment(
                    race = RaceId(it[STARTER_NODES.RACE_ID]!!),
                    nodeId = NodeId(it[STARTER_NODES.NODE_ID]!!),
                )
            }
    }

    @Transactional
    override fun upsertStarterNode(worldId: WorldId, race: RaceId, nodeId: NodeId): StarterNodeAssignment {
        if (!worldExists(worldId)) throw WorldEditingError.WorldNotFound(worldId)
        if (races.byId(race) == null) throw WorldEditingError.UnknownRace(race)
        val terrain = requireNodeTerrainInWorld(worldId, nodeId)
        if (!balance.isTraversable(terrain)) {
            throw WorldEditingError.StarterNodeNotTraversable(nodeId, terrain)
        }
        upsertStarterNodeRow(race, nodeId, worldId)
        return StarterNodeAssignment(race = race, nodeId = nodeId)
    }

    private fun requireNodeTerrainInWorld(worldId: WorldId, nodeId: NodeId): Terrain {
        val nodeRow = dsl.select(NODES.TERRAIN, REGIONS.WORLD_ID)
            .from(NODES)
            .join(REGIONS).on(REGIONS.ID.eq(NODES.REGION_ID))
            .where(NODES.ID.eq(nodeId.value))
            .fetchOne()
        if (nodeRow == null || nodeRow[REGIONS.WORLD_ID] != worldId.value) {
            throw WorldEditingError.NodeNotInWorld(worldId, nodeId)
        }
        return Terrain.valueOf(nodeRow[NODES.TERRAIN]!!)
    }

    /**
     * Translates the FK-violation race (concurrent admin DELETE between the SELECT and the
     * INSERT under READ COMMITTED) into the typed [WorldEditingError.NodeNotInWorld] so the
     * controller returns 404 instead of 500.
     */
    private fun upsertStarterNodeRow(race: RaceId, nodeId: NodeId, worldId: WorldId) {
        try {
            dsl.insertInto(STARTER_NODES)
                .set(STARTER_NODES.RACE_ID, race.value)
                .set(STARTER_NODES.NODE_ID, nodeId.value)
                .onConflict(STARTER_NODES.RACE_ID)
                .doUpdate()
                .set(STARTER_NODES.NODE_ID, nodeId.value)
                .execute()
        } catch (_: org.springframework.dao.DataIntegrityViolationException) {
            throw WorldEditingError.NodeNotInWorld(worldId, nodeId)
        }
    }

    @Transactional
    override fun removeStarterNode(worldId: WorldId, race: RaceId): Boolean {
        if (!worldExists(worldId)) throw WorldEditingError.WorldNotFound(worldId)
        return dsl.deleteFrom(STARTER_NODES)
            .where(STARTER_NODES.RACE_ID.eq(race.value))
            .execute() > 0
    }

    /**
     * Uses the current tick as `last_regen_at_tick` so newly-painted tiles regen from their
     * birth, not from tick 0 — otherwise a paint at tick 1_000_000 would immediately top up
     * by `1_000_000 / interval` regen events on the first read.
     */
    private fun seedResources(nodes: List<Node>, worldSeed: Long) {
        if (nodes.isEmpty()) return
        val rolls = nodes.flatMap { resourceSpawner.rollFor(it, worldSeed) }
        if (rolls.isEmpty()) return
        resourceStore.seed(rolls, tick = tickClock.currentTick())
    }

    private fun worldExists(id: WorldId): Boolean =
        dsl.fetchExists(dsl.selectOne().from(WORLDS).where(WORLDS.ID.eq(id.value)))

    private fun loadNeighbors(worldId: WorldId): Map<Long, Set<RegionId>> =
        dsl.select(REGION_NEIGHBORS.REGION_ID, REGION_NEIGHBORS.NEIGHBOR_ID)
            .from(REGION_NEIGHBORS)
            .join(REGIONS).on(REGIONS.ID.eq(REGION_NEIGHBORS.REGION_ID))
            .where(REGIONS.WORLD_ID.eq(worldId.value))
            .fetch()
            .groupBy({ it[REGION_NEIGHBORS.REGION_ID]!! }, { RegionId(it[REGION_NEIGHBORS.NEIGHBOR_ID]!!) })
            .mapValues { (_, list) -> list.toSet() }

    private fun loadNodesFor(regionId: RegionId): List<Node> {
        val rows = dsl.select(NODES.ID, NODES.REGION_ID, NODES.Q, NODES.R, NODES.TERRAIN, NODES.PVP_ENABLED)
            .from(NODES)
            .where(NODES.REGION_ID.eq(regionId.value))
            .orderBy(NODES.ID.asc())
            .fetch()
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[NODES.ID]!! }
        val adjacencyByFrom = dsl.select(NODE_ADJACENCY.FROM_NODE_ID, NODE_ADJACENCY.TO_NODE_ID)
            .from(NODE_ADJACENCY)
            .where(NODE_ADJACENCY.FROM_NODE_ID.`in`(ids))
            .fetch()
            .groupBy({ it[NODE_ADJACENCY.FROM_NODE_ID]!! }, { NodeId(it[NODE_ADJACENCY.TO_NODE_ID]!!) })
            .mapValues { (_, list) -> list.toSet() }
        return rows.map { row ->
            val id = NodeId(row[NODES.ID]!!)
            Node(
                id = id,
                regionId = RegionId(row[NODES.REGION_ID]!!),
                q = row[NODES.Q]!!,
                r = row[NODES.R]!!,
                terrain = Terrain.valueOf(row[NODES.TERRAIN]!!),
                adjacency = adjacencyByFrom[id.value].orEmpty(),
                pvpEnabled = row[NODES.PVP_ENABLED] ?: true,
            )
        }
    }

    private fun toWorld(row: org.jooq.Record): World = World(
        id = WorldId(row[WORLDS.ID]!!),
        name = row[WORLDS.NAME]!!,
        nodeCount = row[WORLDS.NODE_COUNT]!!,
        nodeSize = row[WORLDS.NODE_SIZE]!!,
        frequency = row[WORLDS.FREQUENCY]!!,
        createdAt = row[WORLDS.CREATED_AT]!!.toInstant(),
    )

    private fun toRegion(row: org.jooq.Record, id: RegionId, neighbors: Set<RegionId>): Region = Region(
        id = id,
        worldId = WorldId(row[REGIONS.WORLD_ID]!!),
        sphereIndex = row[REGIONS.SPHERE_INDEX]!!,
        biome = row[REGIONS.BIOME]?.let(Biome::valueOf),
        climate = row[REGIONS.CLIMATE]?.let(Climate::valueOf),
        centroid = Vec3(row[REGIONS.CENTROID_X]!!, row[REGIONS.CENTROID_Y]!!, row[REGIONS.CENTROID_Z]!!),
        faceVertices = decodeVertices(row[REGIONS.FACE_VERTICES]!!),
        neighbors = neighbors,
    )

    private fun encodeVertices(vertices: List<Vec3>): JSON {
        val raw: List<List<Double>> = vertices.map { listOf(it.x, it.y, it.z) }
        return JSON.valueOf(mapper.writeValueAsString(raw))
    }

    private fun decodeVertices(jsonb: JSON): List<Vec3> {
        val raw: List<List<Double>> = mapper.readValue(jsonb.data(), VERTICES_TYPE)
        return raw.map(Vec3.Companion::of)
    }

    private companion object {
        private val VERTICES_TYPE = object : TypeReference<List<List<Double>>>() {}
        private val HEX_NEIGHBOR_OFFSETS = listOf(
            1 to 0, -1 to 0,
            0 to 1, 0 to -1,
            1 to -1, -1 to 1,
        )
    }
}
