package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AdminAttributeOverrides
import dev.gvart.genesara.player.AdminSkillXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerk
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentPerksSnapshot
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.RecordClassOfferOutcome
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.classes.Level10ChoiceEmitter
import dev.gvart.genesara.world.internal.classes.Level50EvolutionEmitter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AgentAdminControllerTest {

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")
    private val owner = PlayerId(UUID.randomUUID())
    private val agent = Agent(
        id = AgentId(UUID.randomUUID()),
        owner = owner,
        name = "Ada",
        classId = null,
        race = RaceId("human_commoner"),
        level = 4,
        xpCurrent = 30,
        xpToNext = 400,
        unspentAttributePoints = 2,
        attributes = AgentAttributes.DEFAULT,
        authority = 7,
        fame = 12,
    )
    private val swordSkill = Skill(
        id = SkillId("SWORD"),
        displayName = "Sword",
        description = "",
        category = SkillCategory.COMBAT,
    )
    private val swordPerk = Perk(
        id = PerkId("SWORD_BLEEDER"),
        skill = swordSkill.id,
        milestoneLevel = 50,
        displayName = "Bleeder",
        description = "",
        effect = PerkEffect.PassiveAura(target = dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 1),
    )

    private lateinit var agents: StubAgentRegistry
    private lateinit var skills: StubSkillsRegistry
    private lateinit var perks: StubPerksRegistry
    private lateinit var audit: RecordingAuditLog
    private lateinit var publisher: RecordingPublisher
    private lateinit var emitterAgents: StubAgentRegistry
    private lateinit var behavior: InMemoryBehaviorTracker
    private lateinit var emitter10: Level10ChoiceEmitter
    private lateinit var emitter50: Level50EvolutionEmitter
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        agents = StubAgentRegistry(mutableMapOf(agent.id to agent))
        skills = StubSkillsRegistry()
        perks = StubPerksRegistry()
        audit = RecordingAuditLog()
        publisher = RecordingPublisher()
        behavior = InMemoryBehaviorTracker()
        val classes = StubClassLookup(
            classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            classFor(AgentClass.HUNTER, mapOf("COMBAT" to 0.6, "GATHER" to 0.4)),
        )
        emitter10 = Level10ChoiceEmitter(agents, classes, behavior, publisher)
        emitter50 = Level50EvolutionEmitter(agents, classes, behavior, publisher)
        val controller = AgentAdminController(
            agents = agents,
            skills = skills,
            skillLookup = SingleSkillLookup(swordSkill),
            perks = perks,
            perkLookup = SinglePerkLookup(swordPerk),
            world = StubWorld(),
            safeNodes = NoopSafeNodeGateway,
            auditLog = audit,
            tickClock = FixedTickClock(99L),
            publisher = publisher,
            level10 = emitter10,
            level50 = emitter50,
        )
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionAdvice())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        authenticate()
    }

    private fun authenticate() {
        val authn = UsernamePasswordAuthenticationToken(
            admin,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        SecurityContextHolder.getContext().authentication = authn
    }

    @org.junit.jupiter.api.AfterEach
    fun teardown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `GET detail returns the combined agent view`() {
        mvc.get("/admin/agents/${agent.id.id}").andExpect {
            status { isOk() }
            jsonPath("$.agentId") { value(agent.id.id.toString()) }
            jsonPath("$.name") { value("Ada") }
            jsonPath("$.level") { value(4) }
            jsonPath("$.xp.current") { value(30) }
            jsonPath("$.xp.toNext") { value(400) }
            jsonPath("$.authority") { value(7) }
            jsonPath("$.fame") { value(12) }
        }
    }

    @Test
    fun `GET detail 404 when agent missing`() {
        val other = UUID.randomUUID()
        mvc.get("/admin/agents/$other").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("agent $other is not registered") }
        }
    }

    @Test
    fun `POST xp adds character XP, publishes the gained event, and records audit`() {
        mvc.post("/admin/agents/${agent.id.id}/xp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":50}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.xp.current") { value(80) }
        }

        val gained = publisher.events.filterIsInstance<AgentEvent.CharacterXpGained>().single()
        assertEquals(CharacterXpSource.ADMIN_GRANT, gained.source)
        assertEquals(50, gained.amount)

        val entry = audit.records.single()
        assertEquals("agent.xp_granted", entry.action)
        assertEquals("agent", entry.target)
        assertEquals(agent.id.id.toString(), entry.targetId)
        assertEquals(50, entry.payload["amount"])
    }

    @Test
    fun `POST xp rejects negative amounts with 400`() {
        mvc.post("/admin/agents/${agent.id.id}/xp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":-1}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST level sets the level, audits with force=true`() {
        mvc.post("/admin/agents/${agent.id.id}/level") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"level":17}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.level") { value(17) }
            jsonPath("$.xp.toNext") { value(17 * 100) }
        }

        val entry = audit.records.single()
        assertEquals("agent.level_set", entry.action)
        assertEquals(17, entry.payload["level"])
        assertEquals(true, entry.payload["force"])
    }

    @Test
    fun `POST level rejects zero with 400`() {
        mvc.post("/admin/agents/${agent.id.id}/level") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"level":0}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST attributes sets absolute values and audits`() {
        mvc.post("/admin/agents/${agent.id.id}/attributes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"str":12,"con":8,"unspent":3}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.attributes.strength") { value(12) }
            jsonPath("$.attributes.constitution") { value(8) }
            jsonPath("$.unspentAttributePoints") { value(3) }
        }

        val entry = audit.records.single()
        assertEquals("agent.attributes_set", entry.action)
        assertEquals(12, entry.payload["str"])
        assertEquals(8, entry.payload["con"])
        assertEquals(3, entry.payload["unspent"])
    }

    @Test
    fun `POST attributes rejects empty body with 400`() {
        mvc.post("/admin/agents/${agent.id.id}/attributes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST skill xp upserts ledger, audits with previous and new values`() {
        mvc.post("/admin/agents/${agent.id.id}/skills/SWORD") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"xp":75}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.skillId") { value("SWORD") }
            jsonPath("$.newXp") { value(75) }
            jsonPath("$.crossedMilestones[0]") { value(50) }
        }

        val entry = audit.records.single()
        assertEquals("agent.skill_set", entry.action)
        assertEquals("SWORD", entry.payload["skillId"])
        assertEquals(75, entry.payload["xp"])
        assertEquals(75, entry.payload["newXp"])
    }

    @Test
    fun `POST skill 404 for unknown skill`() {
        mvc.post("/admin/agents/${agent.id.id}/skills/PHANTOM") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"xp":10}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("skill 'PHANTOM' is not in the catalog") }
        }
    }

    @Test
    fun `POST skill rejects missing xp and level`() {
        mvc.post("/admin/agents/${agent.id.id}/skills/SWORD") {
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `DELETE skill force-unequips with a warning and audit force=true`() {
        skills.equip(agent.id, swordSkill.id, slotIndex = 0)

        mvc.delete("/admin/agents/${agent.id.id}/skills/SWORD").andExpect {
            status { isOk() }
            jsonPath("$.removed") { value(true) }
            jsonPath("$.warning") { exists() }
        }

        val entry = audit.records.single()
        assertEquals("agent.skill_force_unequipped", entry.action)
        assertEquals(true, entry.payload["force"])
        assertEquals(true, entry.payload["removed"])
    }

    @Test
    fun `POST perk grants the perk, publishes PerkChosen, audits`() {
        mvc.post("/admin/agents/${agent.id.id}/perks/SWORD_BLEEDER").andExpect {
            status { isOk() }
            jsonPath("$.perkId") { value("SWORD_BLEEDER") }
            jsonPath("$.skillId") { value("SWORD") }
            jsonPath("$.milestone") { value(50) }
        }

        val chosen = publisher.events.filterIsInstance<AgentEvent.PerkChosen>().single()
        assertEquals(swordPerk.id, chosen.perk)

        val entry = audit.records.single()
        assertEquals("agent.perk_granted", entry.action)
        assertEquals("SWORD_BLEEDER", entry.payload["perkId"])
    }

    @Test
    fun `POST perk rejects an unknown perk with 404`() {
        mvc.post("/admin/agents/${agent.id.id}/perks/PHANTOM").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `POST perk rejects when milestone already taken with 400`() {
        perks.recordChoice(agent.id, swordPerk.id, tick = 0L)

        mvc.post("/admin/agents/${agent.id.id}/perks/SWORD_BLEEDER").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `DELETE perk revokes and audits force=true`() {
        perks.recordChoice(agent.id, swordPerk.id, tick = 0L)

        mvc.delete("/admin/agents/${agent.id.id}/perks/SWORD_BLEEDER").andExpect {
            status { isOk() }
            jsonPath("$.perkId") { value("SWORD_BLEEDER") }
        }

        val entry = audit.records.single()
        assertEquals("agent.perk_revoked", entry.action)
        assertEquals(true, entry.payload["force"])
    }

    @Test
    fun `DELETE perk 404 when the agent does not hold it`() {
        mvc.delete("/admin/agents/${agent.id.id}/perks/SWORD_BLEEDER").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `POST class assigns regardless of the offer gate and audits force=true`() {
        mvc.post("/admin/agents/${agent.id.id}/class") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classId":"SCOUT"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.classId") { value("SCOUT") }
        }

        val chosen = publisher.events.filterIsInstance<AgentEvent.ClassChosen>().single()
        assertEquals(AgentClass.SCOUT, chosen.classId)

        val entry = audit.records.single()
        assertEquals("agent.class_set", entry.action)
        assertEquals("SCOUT", entry.payload["classId"])
        assertEquals(true, entry.payload["force"])
    }

    @Test
    fun `POST class rejects an unknown class with 400`() {
        mvc.post("/admin/agents/${agent.id.id}/class") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classId":"WIZARD"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST class offers re-opens the L10 gate when unclassed and at threshold`() {
        agents.replace(
            agent.copy(
                level = 10,
                classId = null,
                offeredClasses = ClassOffer(AgentClass.SOLDIER, AgentClass.HUNTER),
            ),
        )
        repeat(20) { _ -> behavior.record(agent.id, ActionCategory.COMBAT, 0L) }

        mvc.post("/admin/agents/${agent.id.id}/class/offers").andExpect {
            status { isOk() }
        }

        val emitted = publisher.events.filterIsInstance<AgentEvent.ClassChoiceOffered>().single()
        assertEquals(setOf(AgentClass.SOLDIER, AgentClass.HUNTER), emitted.candidates.toSet())

        val entry = audit.records.single()
        assertEquals("agent.class_offers_reopened", entry.action)
        assertEquals(true, entry.payload["force"])
        assertEquals(false, entry.payload["hadClass"])
    }

    private class StubAgentRegistry(
        private val byId: MutableMap<AgentId, Agent>,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> =
            byId.values.filter { it.owner == owner }

        fun replace(agent: Agent) {
            byId[agent.id] = agent
        }

        override fun addCharacterXp(agentId: AgentId, delta: Int): AddCharacterXpOutcome? {
            if (delta < 0) return AddCharacterXpOutcome.NegativeDelta
            val current = byId[agentId] ?: return null
            val newXp = current.xpCurrent + delta
            byId[agentId] = current.copy(xpCurrent = newXp)
            return AddCharacterXpOutcome.Granted(
                previousLevel = current.level,
                currentLevel = current.level,
                xpCurrent = newXp,
                xpToNext = current.xpToNext,
                unspentAttributePoints = current.unspentAttributePoints,
                accruedDelta = delta,
                cappedAtPendingClassChoice = false,
                cappedAtPendingEvolutionChoice = false,
            )
        }

        override fun adminSetLevel(agentId: AgentId, level: Int): Agent? {
            val current = byId[agentId] ?: return null
            val newXpToNext = level * 100
            val updated = current.copy(
                level = level,
                xpToNext = newXpToNext,
                xpCurrent = current.xpCurrent.coerceAtMost(newXpToNext),
            )
            byId[agentId] = updated
            return updated
        }

        override fun adminSetAttributes(agentId: AgentId, set: AdminAttributeOverrides): Agent? {
            val current = byId[agentId] ?: return null
            val attrs = AgentAttributes(
                strength = set.strength ?: current.attributes.strength,
                dexterity = set.dexterity ?: current.attributes.dexterity,
                constitution = set.constitution ?: current.attributes.constitution,
                perception = set.perception ?: current.attributes.perception,
                intelligence = set.intelligence ?: current.attributes.intelligence,
                luck = set.luck ?: current.attributes.luck,
            )
            val updated = current.copy(
                attributes = attrs,
                unspentAttributePoints = set.unspent ?: current.unspentAttributePoints,
            )
            byId[agentId] = updated
            return updated
        }

        override fun adminAssignClass(agentId: AgentId, classId: AgentClass): Agent? {
            val current = byId[agentId] ?: return null
            val updated = current.copy(classId = classId, offeredClasses = null)
            byId[agentId] = updated
            return updated
        }

        override fun adminClearClassAndOffers(agentId: AgentId): Agent? {
            val current = byId[agentId] ?: return null
            val updated = current.copy(classId = null, offeredClasses = null, offeredEvolutions = null)
            byId[agentId] = updated
            return updated
        }

        override fun adminClearPendingOffers(agentId: AgentId): Agent? {
            val current = byId[agentId] ?: return null
            val updated = current.copy(offeredClasses = null, offeredEvolutions = null)
            byId[agentId] = updated
            return updated
        }

        override fun recordPendingClassChoice(agentId: AgentId, offer: ClassOffer): RecordClassOfferOutcome {
            val current = byId[agentId] ?: return RecordClassOfferOutcome.UnknownAgent
            if (current.classId != null) return RecordClassOfferOutcome.AlreadyClassed
            if (current.offeredClasses != null) return RecordClassOfferOutcome.AlreadyOffered(current.offeredClasses!!)
            byId[agentId] = current.copy(offeredClasses = offer)
            return RecordClassOfferOutcome.Recorded
        }
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val xp = mutableMapOf<Pair<AgentId, SkillId>, Int>()
        private val slots = mutableMapOf<Pair<AgentId, SkillId>, Int>()

        fun equip(agent: AgentId, skill: SkillId, slotIndex: Int) {
            slots[agent to skill] = slotIndex
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot {
            val perSkill = xp.filterKeys { it.first == agent }.mapKeys { it.key.second }
                .map { (skill, x) ->
                    skill to AgentSkillState(
                        skill = skill,
                        xp = x,
                        level = x / 10,
                        slotIndex = slots[agent to skill],
                        recommendCount = 0,
                    )
                }.toMap()
            return AgentSkillsSnapshot(perSkill = perSkill, slotCount = 8, slotsFilled = slots.size)
        }

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult =
            AddXpResult.Unslotted

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null

        override fun adminSetSkillXp(agent: AgentId, skill: SkillId, xp: Int): AdminSkillXpResult {
            val key = agent to skill
            val old = this.xp[key] ?: 0
            this.xp[key] = xp
            val crossed = if (xp > old) listOf(50, 100, 150).filter { it in (old + 1)..xp } else emptyList()
            return AdminSkillXpResult(previousXp = old, newXp = xp, crossedMilestones = crossed)
        }

        override fun adminForceUnequip(agent: AgentId, skill: SkillId): Boolean =
            slots.remove(agent to skill) != null
    }

    private class StubPerksRegistry : AgentPerksRegistry {
        private val held = mutableMapOf<Pair<AgentId, PerkId>, Long>()

        override fun snapshot(agent: AgentId): AgentPerksSnapshot {
            val perks = held.filterKeys { it.first == agent }.map { (key, tick) ->
                AgentPerk(skill = SkillId("SWORD"), milestoneLevel = 50, perkId = key.second, chosenAtTick = tick)
            }
            return AgentPerksSnapshot(perks)
        }

        override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult {
            val key = agent to perk
            if (held.containsKey(key)) {
                return RecordPerkResult.MilestoneAlreadyChosen(SkillId("SWORD"), 50, perk)
            }
            held[key] = tick
            return RecordPerkResult.Recorded
        }

        override fun adminRevoke(agent: AgentId, perk: PerkId): Boolean =
            held.remove(agent to perk) != null
    }

    private class RecordingAuditLog : AdminAuditLog {
        val records = mutableListOf<AdminAuditEntry>()

        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long {
            records += AdminAuditEntry(
                seq = records.size.toLong() + 1,
                adminId = adminId,
                action = action,
                target = target,
                targetId = targetId,
                payload = payload,
                tick = tick,
                occurredAt = Instant.EPOCH,
            )
            return records.size.toLong()
        }

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> =
            records.filter { it.seq > after }.take(limit)
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
        override fun publishEvent(event: ApplicationEvent) {
            events += event
        }
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }

    private class SingleSkillLookup(private val only: Skill) : SkillLookup {
        override fun byId(id: SkillId): Skill? = if (id == only.id) only else null
        override fun all(): List<Skill> = listOf(only)
    }

    private class SinglePerkLookup(private val only: Perk) : PerkLookup {
        override fun byId(id: PerkId): Perk? = if (id == only.id) only else null
        override fun choicesAt(skill: SkillId, milestoneLevel: Int) = null
        override fun choicesFor(skill: SkillId) = emptyList<dev.gvart.genesara.player.PerkChoice>()
        override fun all(): List<Perk> = listOf(only)
    }

    private object NoopSafeNodeGateway : AgentSafeNodeGateway {
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {}
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) {}
    }

    private class StubWorld : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private class InMemoryBehaviorTracker : BehaviorTracker {
        private val counts = mutableMapOf<AgentId, MutableMap<ActionCategory, Int>>()
        override fun record(agent: AgentId, category: ActionCategory, tick: Long) {
            counts.getOrPut(agent) { mutableMapOf() }.merge(category, 1, Int::plus)
        }
        override fun snapshotFor(agent: AgentId): Map<ActionCategory, Int> = counts[agent] ?: emptyMap()
        override fun markBaseline(agent: AgentId) {}
        override fun snapshotForWindow(agent: AgentId): Map<ActionCategory, Int> = counts[agent] ?: emptyMap()
    }

    private class StubClassLookup(private val byId: Map<AgentClass, ClassDefinition>) : ClassLookup by NoOpClassLookup {
        constructor(vararg defs: ClassDefinition) : this(defs.associateBy { it.id })
        override fun byId(classId: AgentClass): ClassDefinition? = byId[classId]
        override fun baseClasses(): List<ClassDefinition> = byId.values.toList()
    }

    private fun classFor(id: AgentClass, multipliers: Map<String, Double>): ClassDefinition =
        ClassDefinition(
            id = id,
            displayName = id.name,
            description = "",
            sightRange = 3,
            primarySkills = emptySet(),
            neutralSkills = emptySet(),
            forbiddenCombatSkills = emptySet(),
            damageMultipliers = emptyMap(),
            behaviorFingerprint = multipliers,
        )
}
