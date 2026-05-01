package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.world.HexUpsert
import dev.gvart.genesara.world.RegionGeometry
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldEditingGateway
import dev.gvart.genesara.world.WorldId
import jakarta.validation.Valid
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
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/worlds")
internal class WorldEditorController(
    private val gateway: WorldEditingGateway,
) {

    @GetMapping
    fun list(): List<WorldDto> = gateway.listWorlds().map { it.toDto() }

    @PostMapping
    fun create(@Valid @RequestBody req: CreateWorldRequest): ResponseEntity<WorldDto> = with(req) {
        val world = gateway.createWorld(name, nodeCount, nodeSize)
        ResponseEntity.status(HttpStatus.CREATED).body(world.toDto())
    }

    @GetMapping("/{worldId}")
    fun getOne(@PathVariable worldId: Long): WorldDto =
        (gateway.getWorld(WorldId(worldId)) ?: throw notFound("World not found")).toDto()

    @GetMapping("/{worldId}/nodes")
    fun listGlobeNodes(@PathVariable worldId: Long): List<GlobeNodeDto> {
        val regions = gateway.listRegions(WorldId(worldId))
        val sphereByRegionId = regions.associate { it.id.value to it.sphereIndex }
        return regions.map { it.toDto(sphereByRegionId) }
    }

    @PostMapping("/{worldId}/nodes")
    fun upsertGlobeNode(
        @PathVariable worldId: Long,
        @Valid @RequestBody req: UpsertGlobeNodeRequest,
    ): ResponseEntity<GlobeNodeDto> = with(req) {
        val region = gateway.upsertRegionBiome(WorldId(worldId), sphereIndex, biome, climate, geometryOrNull())
        val sphereByRegionId = sphereByRegionIdOf(WorldId(worldId))
        ResponseEntity.status(HttpStatus.CREATED).body(region.toDto(sphereByRegionId))
    }

    @GetMapping("/{worldId}/nodes/{sphereIndex}")
    fun getGlobeNode(@PathVariable worldId: Long, @PathVariable sphereIndex: Int): GlobeNodeDto {
        val region = gateway.getRegion(WorldId(worldId), sphereIndex) ?: throw notFound("Region not found")
        val sphereByRegionId = sphereByRegionIdOf(WorldId(worldId))
        return region.toDto(sphereByRegionId)
    }

    @PatchMapping("/{worldId}/nodes/{sphereIndex}")
    fun patchGlobeNode(
        @PathVariable worldId: Long,
        @PathVariable sphereIndex: Int,
        @Valid @RequestBody req: PatchGlobeNodeRequest,
    ): GlobeNodeDto {
        val region = gateway.patchRegion(WorldId(worldId), sphereIndex, req.biome, req.climate)
        return region.toDto(sphereByRegionIdOf(WorldId(worldId)))
    }

    @GetMapping("/{worldId}/nodes/{sphereIndex}/hexes")
    fun getHexes(
        @PathVariable worldId: Long,
        @PathVariable sphereIndex: Int,
        @RequestParam(required = false) radius: Int?,
    ): List<HexNodeDto> {
        val r = (radius ?: DEFAULT_HEX_RADIUS).coerceIn(MIN_HEX_RADIUS, MAX_HEX_RADIUS)
        return gateway.getOrSeedHexes(WorldId(worldId), sphereIndex, r).map { it.toDto() }
    }

    @PatchMapping("/{worldId}/nodes/{sphereIndex}/hexes")
    fun patchHexes(
        @PathVariable worldId: Long,
        @PathVariable sphereIndex: Int,
        @Valid @RequestBody req: PatchHexesRequest,
    ): PatchHexesResponse {
        val tiles = req.nodes.map { HexUpsert(id = it.id, q = it.q, r = it.r, terrain = it.terrain) }
        val updated = gateway.mergeHexes(WorldId(worldId), sphereIndex, tiles)
        return PatchHexesResponse(updated)
    }

    private fun sphereByRegionIdOf(worldId: WorldId): Map<Long, Int> =
        gateway.listRegions(worldId).associate { it.id.value to it.sphereIndex }

    private fun UpsertGlobeNodeRequest.geometryOrNull(): RegionGeometry? {
        val verts = faceVertices ?: return null
        val c = centroid ?: return null
        val n = neighborIndices ?: return null
        return RegionGeometry(
            centroid = c.toVec3(),
            faceVertices = verts.map { it.toVec3() },
            neighborSphereIndices = n,
        )
    }

    private fun Vec3Dto.toVec3() = Vec3(x, y, z)

    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)

    private companion object {
        const val DEFAULT_HEX_RADIUS = 12
        const val MIN_HEX_RADIUS = 1
        const val MAX_HEX_RADIUS = 80
    }
}
