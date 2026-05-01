package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeAssignment
import dev.gvart.genesara.world.WorldEditingGateway
import dev.gvart.genesara.world.WorldId
import org.springframework.http.HttpStatus
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
 * chain (matched by `/api/worlds/{worldId}` in [SecurityConfig]) gates it automatically -- no
 * extra security wiring needed here.
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
        @RequestBody req: UpsertStarterNodeRequest,
    ): ResponseEntity<StarterNodeDto> {
        val nodeId = req.nodeId ?: throw EditorHttpError(HttpStatus.BAD_REQUEST, "nodeId is required")
        val race = parseRaceId(raceId)
        val assignment = gateway.upsertStarterNode(WorldId(worldId), race, NodeId(nodeId))
        return ResponseEntity.status(HttpStatus.OK).body(assignment.toDto())
    }

    @DeleteMapping("/{raceId}")
    fun remove(@PathVariable worldId: Long, @PathVariable raceId: String): ResponseEntity<Void> {
        val removed = gateway.removeStarterNode(WorldId(worldId), parseRaceId(raceId))
        return if (removed) ResponseEntity.noContent().build()
        else ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    }

    private fun parseRaceId(raceId: String): RaceId {
        if (raceId.isBlank()) throw EditorHttpError(HttpStatus.BAD_REQUEST, "raceId is required")
        return RaceId(raceId)
    }
}

data class UpsertStarterNodeRequest(val nodeId: Long?)

data class StarterNodeDto(val raceId: String, val nodeId: Long)

private fun StarterNodeAssignment.toDto() = StarterNodeDto(
    raceId = race.value,
    nodeId = nodeId.value,
)
