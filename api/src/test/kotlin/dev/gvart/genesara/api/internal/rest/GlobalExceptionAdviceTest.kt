package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.UsernameAlreadyExists
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldEditingError
import dev.gvart.genesara.world.WorldId
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals

/**
 * Unit-level coverage of every domain-exception → ProblemDetail mapping. Keeps the
 * status codes and detail strings under test even when the calling controllers don't
 * exercise every branch.
 */
class GlobalExceptionAdviceTest {

    private val advice = GlobalExceptionAdvice()

    @Test
    fun `WorldNotFound maps to 404 with stable detail`() {
        val pd = advice.worldEditing(WorldEditingError.WorldNotFound(WorldId(1L)))
        assertEquals(HttpStatus.NOT_FOUND.value(), pd.status)
        assertEquals("World not found", pd.detail)
    }

    @Test
    fun `RegionNotFound maps to 404 with stable detail`() {
        val pd = advice.worldEditing(WorldEditingError.RegionNotFound(WorldId(1L), 0))
        assertEquals(HttpStatus.NOT_FOUND.value(), pd.status)
        assertEquals("Region not found", pd.detail)
    }

    @Test
    fun `NodeNotInWorld maps to 404`() {
        val pd = advice.worldEditing(WorldEditingError.NodeNotInWorld(WorldId(1L), NodeId(2L)))
        assertEquals(HttpStatus.NOT_FOUND.value(), pd.status)
        assertEquals("Node not found in this world", pd.detail)
    }

    @Test
    fun `GeometryRequired maps to 400 with the underlying message`() {
        val pd = advice.worldEditing(WorldEditingError.GeometryRequired())
        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.status)
        assertEquals(
            "face_vertices, centroid, and neighbor_indices required for new face",
            pd.detail,
        )
    }

    @Test
    fun `UnknownRace maps to 400`() {
        val pd = advice.worldEditing(WorldEditingError.UnknownRace(RaceId("ALIEN")))
        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.status)
        assertEquals("Unknown race", pd.detail)
    }

    @Test
    fun `StarterNodeNotTraversable maps to 400`() {
        val pd = advice.worldEditing(
            WorldEditingError.StarterNodeNotTraversable(NodeId(1L), Terrain.OCEAN),
        )
        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.status)
        assertEquals("Starter node terrain is not traversable", pd.detail)
    }

    @Test
    fun `UsernameAlreadyExists maps to 409 with the underlying message`() {
        val pd = advice.usernameTaken(UsernameAlreadyExists("alice"))
        assertEquals(HttpStatus.CONFLICT.value(), pd.status)
        assertEquals("Username 'alice' is already taken", pd.detail)
    }
}
