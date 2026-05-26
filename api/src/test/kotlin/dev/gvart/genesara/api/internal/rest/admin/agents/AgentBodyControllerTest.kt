package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentBodyAdminGateway
import dev.gvart.genesara.world.AgentBodyAdminResult
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.NodeId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import kotlin.test.assertEquals

class AgentBodyControllerTest {

    private val adminId = AdminId(UUID.randomUUID())
    private val admin = Admin(adminId, "ops")
    private val agentId = UUID.randomUUID()

    private lateinit var gateway: StubGateway
    private lateinit var safeNodes: StubSafeNodes
    private lateinit var audit: StubAudit
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        gateway = StubGateway()
        safeNodes = StubSafeNodes()
        audit = StubAudit()
        mvc = MockMvcBuilders.standaloneSetup(
            AgentBodyController(gateway, safeNodes, audit, FixedTickClock(42L)),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(GlobalExceptionAdvice())
            .build()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            admin, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
    }

    @AfterEach
    fun clear() { SecurityContextHolder.clearContext() }

    @Test
    fun `gauges applies the gateway result and writes audit`() {
        gateway.gaugesResult = AgentBodyAdminResult.GaugesUpdated(
            mapOf("hp" to 50, "thirst" to 80),
        )

        mvc.post("/admin/agents/$agentId/gauges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"hp":50,"thirst":80}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.applied.hp") { value(50) }
            jsonPath("$.applied.thirst") { value(80) }
        }

        val entry = audit.entries.single()
        assertEquals("agent.gauges", entry.action)
        assertEquals("agent", entry.target)
        assertEquals(agentId.toString(), entry.targetId)
        assertEquals(mapOf("hp" to 50 as Any?, "thirst" to 80 as Any?), entry.payload)
        assertEquals(42L, entry.tick)
    }

    @Test
    fun `gauges rejects an empty body with 400`() {
        mvc.post("/admin/agents/$agentId/gauges") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("at least one gauge field must be set") }
        }
        assertEquals(0, audit.entries.size)
    }

    @Test
    fun `gauges returns 404 when the agent body is missing`() {
        gateway.gaugesResult = AgentBodyAdminResult.AgentNotFound(AgentId(agentId))

        mvc.post("/admin/agents/$agentId/gauges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"hp":10}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("agent $agentId has no body row") }
        }
        assertEquals(0, audit.entries.size)
    }

    @Test
    fun `gauges forwards negative values to the gateway for clamping`() {
        gateway.gaugesResult = AgentBodyAdminResult.GaugesUpdated(mapOf("hp" to 0))

        mvc.post("/admin/agents/$agentId/gauges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"hp":-1}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.applied.hp") { value(0) }
        }

        assertEquals(-1, gateway.lastGaugeInputs["hp"])
    }

    @Test
    fun `position teleports and writes audit with admin cause`() {
        gateway.teleportResult = AgentBodyAdminResult.Teleported(
            from = NodeId(100),
            to = NodeId(200),
            crossedWorld = true,
        )

        mvc.post("/admin/agents/$agentId/position") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nodeId":200}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.from") { value(100) }
            jsonPath("$.to") { value(200) }
            jsonPath("$.crossedWorld") { value(true) }
        }

        val entry = audit.entries.single()
        assertEquals("agent.teleport", entry.action)
        assertEquals("admin", entry.payload["cause"])
        assertEquals(200L, (entry.payload["to"] as Number).toLong())
        assertEquals(100L, (entry.payload["from"] as Number).toLong())
        assertEquals(true, entry.payload["crossedWorld"])
    }

    @Test
    fun `position returns 404 when the target node is missing`() {
        gateway.teleportResult = AgentBodyAdminResult.NodeNotFound(NodeId(99))

        mvc.post("/admin/agents/$agentId/position") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nodeId":99}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("node 99 not found") }
        }
    }

    @Test
    fun `safe-node delegates to the gateway and writes audit`() {
        mvc.post("/admin/agents/$agentId/safe-node") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nodeId":777}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.nodeId") { value(777) }
        }

        assertEquals(1, safeNodes.sets.size)
        val (storedAgent, storedNode, storedTick) = safeNodes.sets.single()
        assertEquals(AgentId(agentId), storedAgent)
        assertEquals(NodeId(777), storedNode)
        assertEquals(42L, storedTick)

        val entry = audit.entries.single()
        assertEquals("agent.safe_node", entry.action)
        assertEquals(777L, (entry.payload["nodeId"] as Number).toLong())
    }

    @Test
    fun `respawn calls force-respawn and writes audit`() {
        gateway.respawnResult = AgentBodyAdminResult.Respawned(
            at = NodeId(555),
            fromCheckpoint = false,
        )

        mvc.post("/admin/agents/$agentId/respawn") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.at") { value(555) }
            jsonPath("$.fromCheckpoint") { value(false) }
        }

        val entry = audit.entries.single()
        assertEquals("agent.force_respawn", entry.action)
        assertEquals(555L, (entry.payload["at"] as Number).toLong())
        assertEquals(false, entry.payload["fromCheckpoint"])
    }

    @Test
    fun `respawn returns 409 when no spawnable node exists`() {
        gateway.respawnResult = AgentBodyAdminResult.NoSpawnableNode(AgentId(agentId))

        mvc.post("/admin/agents/$agentId/respawn") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("no safe node and no spawnable fallback for $agentId") }
        }
    }

    private class StubGateway : AgentBodyAdminGateway {
        var gaugesResult: AgentBodyAdminResult = AgentBodyAdminResult.GaugesUpdated(emptyMap())
        var teleportResult: AgentBodyAdminResult = AgentBodyAdminResult.Teleported(null, NodeId(0), false)
        var respawnResult: AgentBodyAdminResult = AgentBodyAdminResult.Respawned(NodeId(0), false)
        var lastGaugeInputs: Map<String, Int?> = emptyMap()

        override fun setGauges(
            agent: AgentId,
            hp: Int?, stamina: Int?, mana: Int?, hunger: Int?, thirst: Int?, sleep: Int?,
        ): AgentBodyAdminResult {
            lastGaugeInputs = mapOf(
                "hp" to hp, "stamina" to stamina, "mana" to mana,
                "hunger" to hunger, "thirst" to thirst, "sleep" to sleep,
            )
            return gaugesResult
        }

        override fun teleport(agent: AgentId, nodeId: NodeId, tick: Long): AgentBodyAdminResult = teleportResult
        override fun forceRespawn(agent: AgentId, tick: Long): AgentBodyAdminResult = respawnResult
    }

    private class StubSafeNodes : AgentSafeNodeGateway {
        val sets = mutableListOf<Triple<AgentId, NodeId, Long>>()
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {
            sets += Triple(agentId, nodeId, tick)
        }
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) {}
    }

    private class StubAudit : AdminAuditLog {
        data class Recorded(
            val seq: Long,
            val adminId: AdminId,
            val action: String,
            val target: String,
            val targetId: String?,
            val payload: Map<String, Any?>,
            val tick: Long,
        )
        val entries = mutableListOf<Recorded>()
        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long {
            entries += Recorded(entries.size + 1L, adminId, action, target, targetId, payload, tick)
            return entries.size.toLong()
        }
        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = emptyList()
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
