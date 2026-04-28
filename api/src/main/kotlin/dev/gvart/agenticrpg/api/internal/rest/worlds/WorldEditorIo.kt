package dev.gvart.agenticrpg.api.internal.rest.worlds

import com.fasterxml.jackson.annotation.JsonProperty

data class ErrorResponse(val error: String)

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
    val biome: String?,
    val climate: String?,
    @JsonProperty("face_vertices") val faceVertices: List<List<Double>>,
    val centroid: List<Double>,
    @JsonProperty("neighbor_indices") val neighborIndices: List<Int>,
)

data class HexNodeDto(
    val id: Long,
    val q: Int,
    val r: Int,
    val terrain: String,
)

data class CreateWorldRequest(
    val name: String?,
    @JsonProperty("node_count") val nodeCount: Double?,
    @JsonProperty("node_size") val nodeSize: Double?,
)

data class UpsertGlobeNodeRequest(
    @JsonProperty("sphere_index") val sphereIndex: Int?,
    val biome: String?,
    val climate: String?,
    @JsonProperty("face_vertices") val faceVertices: List<List<Double>>?,
    val centroid: List<Double>?,
    @JsonProperty("neighbor_indices") val neighborIndices: List<Int>?,
)

data class HexUpsertDto(
    val id: Long?,
    val q: Int,
    val r: Int,
    val terrain: String,
)

data class PatchHexesRequest(
    val nodes: List<HexUpsertDto>?,
)

data class PatchHexesResponse(val updated: Int)
