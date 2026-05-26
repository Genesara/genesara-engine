package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.api.internal.rest.worlds.MaybeSetDeserializer
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.NpcZoneScope
import tools.jackson.databind.annotation.JsonDeserialize
import java.util.UUID

data class CreateNpcZoneRequest(
    val scope: NpcZoneScope,
    val regionId: Long? = null,
    val nodeId: Long? = null,
    val weights: Map<String, Int>,
    val maxConcurrent: Int,
    val respawnTicks: Int? = null,
    val active: Boolean = true,
)

data class PatchNpcZoneRequest(
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val weights: MaybeSet<Map<String, Int>> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val maxConcurrent: MaybeSet<Int> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val respawnTicks: MaybeSet<Int?> = MaybeSet.Skip,
    @JsonDeserialize(using = MaybeSetDeserializer::class)
    val active: MaybeSet<Boolean> = MaybeSet.Skip,
)

data class NpcZoneDto(
    val zoneId: UUID,
    val worldId: Long,
    val scope: NpcZoneScope,
    val regionId: Long?,
    val nodeId: Long?,
    val weights: Map<String, Int>,
    val maxConcurrent: Int,
    val respawnTicks: Int?,
    val active: Boolean,
    val createdBy: UUID,
    val createdAtTick: Long,
)
