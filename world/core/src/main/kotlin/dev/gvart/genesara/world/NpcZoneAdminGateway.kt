package dev.gvart.genesara.world

import java.util.UUID

/** Admin-side CRUD for [NpcZone]; validates weights against the NPC catalog and target ownership. */
interface NpcZoneAdminGateway {
    fun list(worldId: WorldId): List<NpcZone>

    fun create(spec: NpcZoneCreateSpec): NpcZone

    fun patch(zoneId: UUID, patch: NpcZonePatch): NpcZone

    fun delete(zoneId: UUID): Boolean
}

data class NpcZoneCreateSpec(
    val worldId: WorldId,
    val scope: NpcZoneScope,
    val regionId: RegionId?,
    val nodeId: NodeId?,
    val weights: Map<NpcType, Int>,
    val maxConcurrent: Int,
    val respawnTicks: Int?,
    val active: Boolean,
    val createdBy: UUID,
    val createdAtTick: Long,
)

data class NpcZonePatch(
    val weights: MaybeSet<Map<NpcType, Int>> = MaybeSet.Skip,
    val maxConcurrent: MaybeSet<Int> = MaybeSet.Skip,
    val respawnTicks: MaybeSet<Int?> = MaybeSet.Skip,
    val active: MaybeSet<Boolean> = MaybeSet.Skip,
)

sealed class NpcZoneAdminError(message: String) : RuntimeException(message) {
    class NotFound(val zoneId: UUID) : NpcZoneAdminError("Zone not found: $zoneId")
    class UnknownNpcTypes(val types: Set<NpcType>) :
        NpcZoneAdminError("Unknown NPC types: ${types.joinToString { it.value }}")
    class WorldMismatch(val expected: WorldId, val actual: WorldId?) :
        NpcZoneAdminError(
            "Target does not belong to world ${expected.value}" +
                (actual?.let { " (found in ${it.value})" } ?: " (not found)")
        )
    class ScopeMismatch(val detail: String) : NpcZoneAdminError("Scope mismatch: $detail")
    class EmptyWeights : NpcZoneAdminError("weights must contain at least one entry")
    class NonPositiveWeight(val type: NpcType, val weight: Int) :
        NpcZoneAdminError("weight for ${type.value} must be positive (was $weight)")
    class NegativeMaxConcurrent(val value: Int) :
        NpcZoneAdminError("max_concurrent must be >= 0 (was $value)")
    class NegativeRespawnTicks(val value: Int) :
        NpcZoneAdminError("respawn_ticks must be >= 0 (was $value)")
}
