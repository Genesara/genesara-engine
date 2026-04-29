package dev.gvart.genesara.world

import dev.gvart.genesara.player.RaceId

interface WorldEditingGateway {

    fun listWorlds(): List<World>

    fun getWorld(id: WorldId): World?

    /**
     * Generates a fresh icosphere world: picks the Goldberg subdivision frequency closest to
     * [requestedNodeCount], generates the mesh, and inserts the [World] plus one [Region] per
     * face (with `biome=null` and `climate=null` for the editor to fill in later).
     *
     * The returned [World.nodeCount] reflects the *actual* face count for the chosen frequency,
     * which may differ from [requestedNodeCount].
     */
    fun createWorld(name: String, requestedNodeCount: Int, nodeSize: Int): World

    /** Regions of [worldId] sorted ascending by [Region.sphereIndex]. */
    fun listRegions(worldId: WorldId): List<Region>

    fun getRegion(worldId: WorldId, sphereIndex: Int): Region?

    /**
     * Upsert path used by the editor's `POST /api/worlds/:id/nodes`. If a region with this
     * [sphereIndex] exists, biome and climate are updated and geometry is left untouched. If it
     * doesn't exist, [geometry] is required and the region is inserted with the supplied
     * geometry.
     *
     * @throws WorldEditingError.GeometryRequired if the face is new and [geometry] is null.
     * @throws WorldEditingError.WorldNotFound if [worldId] is unknown.
     */
    fun upsertRegionBiome(
        worldId: WorldId,
        sphereIndex: Int,
        biome: Biome,
        climate: Climate,
        geometry: RegionGeometry?,
    ): Region

    /**
     * Partial update of a region's [biome] and [climate]. Each [MaybeSet] carries:
     *  - [MaybeSet.Skip] — keep the prior value
     *  - [MaybeSet.Set] with `value=null` — clear the field
     *  - [MaybeSet.Set] with a non-null value — write the field
     */
    fun patchRegion(
        worldId: WorldId,
        sphereIndex: Int,
        biome: MaybeSet<Biome?>,
        climate: MaybeSet<Climate?>,
    ): Region

    /**
     * Returns the hex grid for one region. If the grid hasn't been generated yet, it is
     * deterministically seeded using `radius` and persisted (subsequent calls ignore [radius]
     * and return the persisted grid as-is).
     *
     * @throws WorldEditingError.RegionNotFound if no such face exists.
     */
    fun getOrSeedHexes(worldId: WorldId, sphereIndex: Int, radius: Int): List<Node>

    /**
     * Merges [tiles] into the region's hex grid by `(q, r)` key. Existing tiles keep their id;
     * new tiles get an auto-assigned id. Returns the number of tiles processed (== tiles.size).
     */
    fun mergeHexes(worldId: WorldId, sphereIndex: Int, tiles: List<HexUpsert>): Int

    // --- Starter nodes ---
    //
    // The `starter_nodes` table is keyed globally by raceId (no world_id column) — the
    // engine runs one active world at a time, so per-world starter assignments aren't
    // needed in v1. The admin endpoints below scope the URL by [worldId] for clarity
    // and to enforce that the assigned node lives in that world; the underlying table
    // stays global.

    /**
     * Lists `(race, node)` starter assignments where the node lives in [worldId].
     * Empty when the table is unseeded — fresh worlds use [WorldQueryGateway.randomSpawnableNode]
     * as fallback until an admin populates this.
     *
     * @throws WorldEditingError.WorldNotFound if [worldId] is unknown.
     */
    fun listStarterNodes(worldId: WorldId): List<StarterNodeAssignment>

    /**
     * Upserts a `(race -> node)` mapping. Validates that [nodeId] lives in [worldId]
     * and is on traversable terrain (so a freshly-spawned agent isn't stuck).
     *
     * @throws WorldEditingError.WorldNotFound if [worldId] is unknown.
     * @throws WorldEditingError.NodeNotInWorld if [nodeId] doesn't belong to [worldId].
     * @throws WorldEditingError.StarterNodeNotTraversable if the node's terrain is not
     *   traversable (e.g. OCEAN before boats unlock).
     */
    fun upsertStarterNode(worldId: WorldId, race: RaceId, nodeId: NodeId): StarterNodeAssignment

    /**
     * Removes the `(race -> node)` mapping. Returns true if a row was deleted, false if
     * no mapping existed for [race]. Does NOT validate that the prior mapping pointed
     * at a node in [worldId] — the world-scope is informational on this verb.
     */
    fun removeStarterNode(worldId: WorldId, race: RaceId): Boolean
}

/** A single starter-node assignment row, projected for admin tooling. */
data class StarterNodeAssignment(
    val race: RaceId,
    val nodeId: NodeId,
)

/**
 * Geometry payload for inserting a brand-new icosphere face. Required only when the face
 * doesn't already exist; ignored on updates.
 */
data class RegionGeometry(
    val centroid: Vec3,
    val faceVertices: List<Vec3>,
    val neighborSphereIndices: List<Int>,
)

data class HexUpsert(
    val id: Long?,
    val q: Int,
    val r: Int,
    val terrain: Terrain,
)

/** Three-state field update used by [WorldEditingGateway.patchRegion]. */
sealed interface MaybeSet<out T> {
    data object Skip : MaybeSet<Nothing>
    data class Set<T>(val value: T) : MaybeSet<T>
}

sealed class WorldEditingError(message: String) : RuntimeException(message) {
    class WorldNotFound(val worldId: WorldId) : WorldEditingError("World not found: ${worldId.value}")
    class RegionNotFound(val worldId: WorldId, val sphereIndex: Int) :
        WorldEditingError("Region not found: world=${worldId.value} sphere_index=$sphereIndex")
    class GeometryRequired :
        WorldEditingError("face_vertices, centroid, and neighbor_indices required for new face")
    class NodeNotInWorld(val worldId: WorldId, val nodeId: NodeId) :
        WorldEditingError("Node ${nodeId.value} does not belong to world ${worldId.value}")
    class UnknownRace(val race: RaceId) :
        WorldEditingError("Unknown race id: ${race.value}")
    class StarterNodeNotTraversable(val nodeId: NodeId, val terrain: Terrain) :
        WorldEditingError("Node ${nodeId.value} has non-traversable terrain $terrain — agents would spawn stuck")
}
