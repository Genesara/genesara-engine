package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.api.internal.rest.worlds.MaybeSetDeserializer
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.MaybeSet
import tools.jackson.databind.annotation.JsonDeserialize
import java.util.UUID

data class CreateBuildingRequest(
    val type: BuildingType,
    val status: BuildingStatus? = null,
    val hpCurrent: Int? = null,
    val hpMax: Int? = null,
    val progressSteps: Int? = null,
    val totalSteps: Int? = null,
    val builtByAgentId: UUID? = null,
)

data class PatchBuildingRequest(
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val status: MaybeSet<BuildingStatus?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val hpCurrent: MaybeSet<Int?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val hpMax: MaybeSet<Int?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val progressSteps: MaybeSet<Int?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val totalSteps: MaybeSet<Int?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val builtByAgentId: MaybeSet<UUID?> = MaybeSet.Skip,
)

data class BuildingDto(
    val instanceId: UUID,
    val nodeId: Long,
    val type: BuildingType,
    val status: BuildingStatus,
    val builtByAgentId: UUID,
    val builtAtTick: Long,
    val lastProgressTick: Long,
    val progressSteps: Int,
    val totalSteps: Int,
    val hpCurrent: Int,
    val hpMax: Int,
)
