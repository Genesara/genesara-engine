package dev.gvart.genesara.api.internal.mcp.tools

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NpcId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PrefixedIdsTest {

    private val sampleUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `encodes agent and npc ids with their prefix`() {
        assertEquals("agent:$sampleUuid", PrefixedIds.encodeAgent(AgentId(sampleUuid)))
        assertEquals("npc:$sampleUuid", PrefixedIds.encodeNpc(NpcId(sampleUuid)))
    }

    @Test
    fun `encodeAgent UUID overload matches the AgentId overload`() {
        assertEquals(
            PrefixedIds.encodeAgent(AgentId(sampleUuid)),
            PrefixedIds.encodeAgent(sampleUuid),
        )
    }

    @Test
    fun `parseAgent round-trips through encodeAgent`() {
        val id = AgentId(sampleUuid)
        assertEquals(id, PrefixedIds.parseAgent(PrefixedIds.encodeAgent(id)))
    }

    @Test
    fun `parseNpc round-trips through encodeNpc`() {
        val id = NpcId(sampleUuid)
        assertEquals(id, PrefixedIds.parseNpc(PrefixedIds.encodeNpc(id)))
    }

    @Test
    fun `parseAgent rejects bare UUID, wrong prefix, and malformed UUID`() {
        assertNull(PrefixedIds.parseAgent(sampleUuid.toString()))
        assertNull(PrefixedIds.parseAgent("npc:$sampleUuid"))
        assertNull(PrefixedIds.parseAgent("agent:not-a-uuid"))
        assertNull(PrefixedIds.parseAgent(""))
    }

    @Test
    fun `parseNpc rejects bare UUID, wrong prefix, and malformed UUID`() {
        assertNull(PrefixedIds.parseNpc(sampleUuid.toString()))
        assertNull(PrefixedIds.parseNpc("agent:$sampleUuid"))
        assertNull(PrefixedIds.parseNpc("npc:not-a-uuid"))
        assertNull(PrefixedIds.parseNpc(""))
    }

    @Test
    fun `parseAgentLenient accepts wire-prefixed form`() {
        val id = AgentId(sampleUuid)
        assertEquals(id, PrefixedIds.parseAgentLenient("agent:$sampleUuid"))
    }

    @Test
    fun `parseAgentLenient accepts bare UUID`() {
        val id = AgentId(sampleUuid)
        assertEquals(id, PrefixedIds.parseAgentLenient(sampleUuid.toString()))
    }

    @Test
    fun `parseAgentLenient rejects wrong prefix, malformed UUID, and blank`() {
        assertNull(PrefixedIds.parseAgentLenient("npc:$sampleUuid"))
        assertNull(PrefixedIds.parseAgentLenient("agent:not-a-uuid"))
        assertNull(PrefixedIds.parseAgentLenient("not-a-uuid"))
        assertNull(PrefixedIds.parseAgentLenient(""))
    }

    @Test
    fun `parseAttackTarget dispatches to Agent and Npc variants`() {
        val agent = PrefixedIds.parseAttackTarget("agent:$sampleUuid")
        assertIs<AttackTarget.Agent>(agent)
        assertEquals(AgentId(sampleUuid), agent.id)

        val npc = PrefixedIds.parseAttackTarget("npc:$sampleUuid")
        assertIs<AttackTarget.Npc>(npc)
        assertEquals(NpcId(sampleUuid), npc.id)
    }

    @Test
    fun `parseAttackTarget rejects bare UUID, unknown prefix, and malformed UUID`() {
        assertNull(PrefixedIds.parseAttackTarget(sampleUuid.toString()))
        assertNull(PrefixedIds.parseAttackTarget("boss:$sampleUuid"))
        assertNull(PrefixedIds.parseAttackTarget("agent:not-a-uuid"))
    }
}
