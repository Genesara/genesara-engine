package dev.gvart.agenticrpg.api.internal.rest.worlds

import tools.jackson.databind.JsonNode
import dev.gvart.agenticrpg.world.Biome
import dev.gvart.agenticrpg.world.Climate
import dev.gvart.agenticrpg.world.HexUpsert
import dev.gvart.agenticrpg.world.MaybeSet
import dev.gvart.agenticrpg.world.RegionGeometry
import dev.gvart.agenticrpg.world.Terrain
import dev.gvart.agenticrpg.world.Vec3
import dev.gvart.agenticrpg.world.WorldEditingError
import dev.gvart.agenticrpg.world.WorldEditingGateway
import dev.gvart.agenticrpg.world.WorldId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/worlds")
internal class WorldEditorController(
    private val gateway: WorldEditingGateway,
) {

    @GetMapping
    fun list(): List<WorldDto> = gateway.listWorlds().map { it.toDto() }

    @PostMapping
    fun create(@RequestBody req: CreateWorldRequest): ResponseEntity<WorldDto> {
        val name = req.name?.takeIf { it.isNotBlank() } ?: throw badBody("Invalid body")
        val nodeCount = req.nodeCount?.takeIf { it.isFinite() } ?: throw badBody("Invalid body")
        val nodeSize = req.nodeSize?.takeIf { it.isFinite() } ?: throw badBody("Invalid body")
        val world = gateway.createWorld(name, nodeCount.toInt(), nodeSize.toInt())
        return ResponseEntity.status(HttpStatus.CREATED).body(world.toDto())
    }

    @GetMapping("/{worldId}")
    fun getOne(@PathVariable worldId: Long): WorldDto {
        val world = gateway.getWorld(WorldId(worldId)) ?: throw notFound("Not found")
        return world.toDto()
    }

    @GetMapping("/{worldId}/nodes")
    fun listGlobeNodes(@PathVariable worldId: Long): List<GlobeNodeDto> {
        val regions = mapWorldNotFound { gateway.listRegions(WorldId(worldId)) }
        val sphereByRegionId = regions.associate { it.id.value to it.sphereIndex }
        return regions.map { it.toDto(sphereByRegionId) }
    }

    @PostMapping("/{worldId}/nodes")
    fun upsertGlobeNode(
        @PathVariable worldId: Long,
        @RequestBody req: UpsertGlobeNodeRequest,
    ): ResponseEntity<GlobeNodeDto> {
        val sphereIndex = req.sphereIndex
        val biomeName = req.biome
        val climateName = req.climate
        if (sphereIndex == null || biomeName.isNullOrBlank() || climateName.isNullOrBlank()) {
            throw badBody("sphere_index, biome, and climate are required")
        }
        val biome = parseBiome(biomeName)
        val climate = parseClimate(climateName)
        val geometry = req.geometryOrNull()
        val region = mapWorldNotFound {
            gateway.upsertRegionBiome(WorldId(worldId), sphereIndex, biome, climate, geometry)
        }
        val sphereByRegionId = sphereByRegionIdOf(WorldId(worldId))
        return ResponseEntity.status(HttpStatus.CREATED).body(region.toDto(sphereByRegionId))
    }

    @GetMapping("/{worldId}/nodes/{sphereIndex}")
    fun getGlobeNode(@PathVariable worldId: Long, @PathVariable sphereIndex: Int): GlobeNodeDto {
        val region = gateway.getRegion(WorldId(worldId), sphereIndex) ?: throw notFound("Not found")
        val sphereByRegionId = sphereByRegionIdOf(WorldId(worldId))
        return region.toDto(sphereByRegionId)
    }

    @PatchMapping("/{worldId}/nodes/{sphereIndex}")
    fun patchGlobeNode(
        @PathVariable worldId: Long,
        @PathVariable sphereIndex: Int,
        @RequestBody body: JsonNode,
    ): GlobeNodeDto {
        val biome: MaybeSet<Biome?> = readNullable(body, "biome", ::parseBiome)
        val climate: MaybeSet<Climate?> = readNullable(body, "climate", ::parseClimate)
        val region = try {
            gateway.patchRegion(WorldId(worldId), sphereIndex, biome, climate)
        } catch (_: WorldEditingError.WorldNotFound) {
            throw notFound("World not found")
        } catch (_: WorldEditingError.RegionNotFound) {
            throw notFound("Node not found")
        }
        val sphereByRegionId = sphereByRegionIdOf(WorldId(worldId))
        return region.toDto(sphereByRegionId)
    }

    @GetMapping("/{worldId}/nodes/{sphereIndex}/hexes")
    fun getHexes(
        @PathVariable worldId: Long,
        @PathVariable sphereIndex: Int,
        @RequestParam(required = false) radius: Int?,
    ): List<HexNodeDto> {
        val r = (radius ?: DEFAULT_HEX_RADIUS).coerceIn(MIN_HEX_RADIUS, MAX_HEX_RADIUS)
        val nodes = try {
            gateway.getOrSeedHexes(WorldId(worldId), sphereIndex, r)
        } catch (_: WorldEditingError.RegionNotFound) {
            throw notFound("Globe node not found")
        }
        return nodes.map { it.toDto() }
    }

    @PatchMapping("/{worldId}/nodes/{sphereIndex}/hexes")
    fun patchHexes(
        @PathVariable worldId: Long,
        @PathVariable sphereIndex: Int,
        @RequestBody req: PatchHexesRequest,
    ): PatchHexesResponse {
        val nodes = req.nodes ?: throw badBody("Invalid body")
        val tiles = nodes.map {
            HexUpsert(id = it.id, q = it.q, r = it.r, terrain = parseTerrain(it.terrain))
        }
        val updated = try {
            gateway.mergeHexes(WorldId(worldId), sphereIndex, tiles)
        } catch (_: WorldEditingError.RegionNotFound) {
            throw notFound("Globe node not found")
        }
        return PatchHexesResponse(updated)
    }

    private fun sphereByRegionIdOf(worldId: WorldId): Map<Long, Int> =
        gateway.listRegions(worldId).associate { it.id.value to it.sphereIndex }

    private fun UpsertGlobeNodeRequest.geometryOrNull(): RegionGeometry? {
        val verts = faceVertices ?: return null
        val c = centroid ?: return null
        val n = neighborIndices ?: return null
        if (c.size != 3) throw badBody("Invalid body")
        val centroidVec = Vec3(c[0], c[1], c[2])
        val faceVecs = verts.map { v ->
            if (v.size != 3) throw badBody("Invalid body")
            Vec3(v[0], v[1], v[2])
        }
        return RegionGeometry(
            centroid = centroidVec,
            faceVertices = faceVecs,
            neighborSphereIndices = n,
        )
    }

    private fun parseBiome(name: String): Biome = try {
        Biome.valueOf(name)
    } catch (_: IllegalArgumentException) {
        throw badBody("Invalid body")
    }

    private fun parseClimate(name: String): Climate = try {
        Climate.valueOf(name)
    } catch (_: IllegalArgumentException) {
        throw badBody("Invalid body")
    }

    private fun parseTerrain(name: String): Terrain = try {
        Terrain.valueOf(name)
    } catch (_: IllegalArgumentException) {
        throw badBody("Invalid body")
    }

    /** Tri-state field reader for PATCH bodies: missing → Skip, present-and-null → Set(null), present → Set(parsed). */
    private fun <T> readNullable(body: JsonNode, key: String, parse: (String) -> T): MaybeSet<T?> {
        if (!body.has(key)) return MaybeSet.Skip
        val node = body.get(key)
        return when {
            node == null || node.isNull -> MaybeSet.Set(null)
            node.isTextual -> MaybeSet.Set(parse(node.asText()))
            else -> throw badBody("Invalid body")
        }
    }

    private fun <T> mapWorldNotFound(block: () -> T): T = try {
        block()
    } catch (_: WorldEditingError.WorldNotFound) {
        throw notFound("World not found")
    }

    private fun notFound(message: String) = EditorHttpError(HttpStatus.NOT_FOUND, message)
    private fun badBody(message: String) = EditorHttpError(HttpStatus.BAD_REQUEST, message)

    private companion object {
        const val DEFAULT_HEX_RADIUS = 12
        const val MIN_HEX_RADIUS = 1
        const val MAX_HEX_RADIUS = 80
    }
}
