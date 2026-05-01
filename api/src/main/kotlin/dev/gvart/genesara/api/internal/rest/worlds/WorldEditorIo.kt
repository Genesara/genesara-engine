package dev.gvart.genesara.api.internal.rest.worlds

import com.fasterxml.jackson.annotation.JsonProperty
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.Terrain
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import tools.jackson.databind.annotation.JsonDeserialize

data class WorldDto(
    val id: Long,
    val name: String,
    @JsonProperty("node_count") val nodeCount: Int,
    @JsonProperty("node_size") val nodeSize: Int,
    @JsonProperty("created_at") val createdAt: String,
)

data class GlobeNodeDto(
    val id: Long,
    @JsonProperty("world_id") val worldId: Long,
    @JsonProperty("sphere_index") val sphereIndex: Int,
    val biome: Biome?,
    val climate: Climate?,
    @JsonProperty("face_vertices") val faceVertices: List<Vec3Dto>,
    val centroid: Vec3Dto,
    @JsonProperty("neighbor_indices") val neighborIndices: List<Int>,
)

data class HexNodeDto(
    val id: Long,
    val q: Int,
    val r: Int,
    val terrain: Terrain,
)

data class CreateWorldRequest(
    @field:NotBlank val name: String,
    @field:Positive @JsonProperty("node_count") val nodeCount: Int,
    @field:Positive @JsonProperty("node_size") val nodeSize: Int,
)

data class UpsertGlobeNodeRequest(
    @field:PositiveOrZero @JsonProperty("sphere_index") val sphereIndex: Int,
    val biome: Biome,
    val climate: Climate,
    @field:Valid @JsonProperty("face_vertices") val faceVertices: List<Vec3Dto>?,
    @field:Valid val centroid: Vec3Dto?,
    @JsonProperty("neighbor_indices") val neighborIndices: List<Int>?,
)

/**
 * Tri-state PATCH body for [Biome]/[Climate]: a key absent leaves the field
 * untouched, an explicit `null` clears it, and a value sets it.
 */
data class PatchGlobeNodeRequest(
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val biome: MaybeSet<Biome?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val climate: MaybeSet<Climate?> = MaybeSet.Skip,
)

data class HexUpsertDto(
    val id: Long?,
    val q: Int,
    val r: Int,
    val terrain: Terrain,
)

data class PatchHexesRequest(
    @field:NotEmpty @field:Valid val nodes: List<HexUpsertDto>,
)

data class PatchHexesResponse(val updated: Int)

data class Vec3Dto(val x: Double, val y: Double, val z: Double)
