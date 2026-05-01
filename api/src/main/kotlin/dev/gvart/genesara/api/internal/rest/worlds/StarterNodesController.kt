package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeAssignment
import dev.gvart.genesara.world.WorldEditingGateway
import dev.gvart.genesara.world.WorldId
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin-only CRUD for the `starter_nodes` table that backs race-keyed agent spawn.
 * Lives under `/api/worlds/{worldId}/starter-nodes` so the admin bearer-token security
 * chain (matched by `/api/worlds/{worldId}` in [SecurityConfig]) gates it automatically.
 *
 * The underlying table is global (PK is `race_id`, no `world_id` column). Scoping each
 * verb to a `worldId` is informational and ensures the assigned node lives in that
 * world; setting a race's starter node in world A overwrites world B's setting for the
 * same race. The engine runs one active world at a time in v1, so this is acceptable.
 */
@RestController
@RequestMapping("/api/worlds/{worldId}/starter-nodes")
internal class StarterNodesController(
    private val gateway: WorldEditingGateway,
) {

    @GetMapping
    fun list(@PathVariable worldId: Long): List<StarterNodeDto> =
        gateway.listStarterNodes(WorldId(worldId)).map { it.toDto() }

    @PutMapping("/{raceId}")
    fun upsert(
        @PathVariable worldId: Long,
        @PathVariable raceId: String,
        @Valid @RequestBody req: UpsertStarterNodeRequest,
    ): StarterNodeDto = gateway
        .upsertStarterNode(WorldId(worldId), RaceId(raceId), NodeId(req.nodeId))
        .toDto()

    @DeleteMapping("/{raceId}")
    fun remove(@PathVariable worldId: Long, @PathVariable raceId: String): ResponseEntity<Void> {
        val removed = gateway.removeStarterNode(WorldId(worldId), RaceId(raceId))
        return if (removed) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }
}

data class UpsertStarterNodeRequest(@field:Positive val nodeId: Long)

data class StarterNodeDto(val raceId: String, val nodeId: Long)

private fun StarterNodeAssignment.toDto() = StarterNodeDto(
    raceId = race.value,
    nodeId = nodeId.value,
)
