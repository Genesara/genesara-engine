package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AdminAttributeOverrides
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.classes.Level10ChoiceEmitter
import dev.gvart.genesara.world.internal.classes.Level50EvolutionEmitter
import jakarta.validation.Valid
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/agents/{agentId}")
internal class AgentAdminController(
    private val agents: AgentRegistry,
    private val skills: AgentSkillsRegistry,
    private val skillLookup: SkillLookup,
    private val perks: AgentPerksRegistry,
    private val perkLookup: PerkLookup,
    private val world: WorldQueryGateway,
    private val safeNodes: AgentSafeNodeGateway,
    private val auditLog: AdminAuditLog,
    private val tickClock: TickClock,
    private val publisher: ApplicationEventPublisher,
    private val level10: Level10ChoiceEmitter,
    private val level50: Level50EvolutionEmitter,
) {

    @GetMapping
    fun detail(@PathVariable agentId: UUID): AgentDetailDto =
        requireAgent(AgentId(agentId)).toDetailDto()

    @PostMapping("/xp")
    fun grantXp(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: GrantXpRequest,
    ): AgentDetailDto {
        val target = AgentId(agentId)
        val tick = tickClock.currentTick()
        val outcome = agents.addCharacterXp(target, req.amount)
            ?: throw notFound("agent $agentId is not registered")
        if (outcome is AddCharacterXpOutcome.Granted) {
            val commandId = UUID.randomUUID()
            publisher.publishEvent(
                AgentEvent.CharacterXpGained(
                    agent = target,
                    source = CharacterXpSource.ADMIN_GRANT,
                    amount = outcome.accruedDelta,
                    total = outcome.xpCurrent,
                    toNext = outcome.xpToNext,
                    level = outcome.currentLevel,
                    unspentAttributePoints = outcome.unspentAttributePoints,
                    tick = tick,
                    causedBy = commandId,
                ),
            )
            if (outcome.currentLevel > outcome.previousLevel) {
                publisher.publishEvent(
                    AgentEvent.AgentLeveled(
                        agent = target,
                        fromLevel = outcome.previousLevel,
                        toLevel = outcome.currentLevel,
                        unspentAttributePoints = outcome.unspentAttributePoints,
                        tick = tick,
                        causedBy = commandId,
                    ),
                )
            }
        }
        audit(admin, "agent.xp_granted", target, mapOf("amount" to req.amount), tick)
        return detail(agentId)
    }

    @PostMapping("/level")
    fun setLevel(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: SetLevelRequest,
    ): AgentDetailDto {
        if (req.level < 1) throw badRequest("level must be >= 1")
        val target = AgentId(agentId)
        val updated = agents.adminSetLevel(target, req.level)
            ?: throw notFound("agent $agentId is not registered")
        audit(
            admin,
            "agent.level_set",
            target,
            mapOf("level" to req.level, "force" to true),
            tickClock.currentTick(),
        )
        return updated.toDetailDto()
    }

    @PostMapping("/attributes")
    fun setAttributes(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: SetAttributesRequest,
    ): AgentDetailDto {
        validateAttributeBounds(req)
        val target = AgentId(agentId)
        val updated = agents.adminSetAttributes(
            target,
            AdminAttributeOverrides(
                strength = req.str,
                dexterity = req.dex,
                constitution = req.con,
                perception = req.per,
                intelligence = req.int,
                luck = req.luck,
                unspent = req.unspent,
            ),
        ) ?: throw notFound("agent $agentId is not registered")
        audit(
            admin,
            "agent.attributes_set",
            target,
            buildMap {
                req.str?.let { put("str", it) }
                req.dex?.let { put("dex", it) }
                req.con?.let { put("con", it) }
                req.per?.let { put("per", it) }
                req.int?.let { put("int", it) }
                req.luck?.let { put("luck", it) }
                req.unspent?.let { put("unspent", it) }
            },
            tickClock.currentTick(),
        )
        return updated.toDetailDto()
    }

    @PostMapping("/skills/{skillId}")
    fun setSkill(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable skillId: String,
        @Valid @RequestBody req: SetSkillRequest,
    ): SkillUpdateDto {
        val target = AgentId(agentId)
        requireAgent(target)
        val skill = skillLookup.byId(SkillId(skillId))
            ?: throw notFound("skill '$skillId' is not in the catalog")
        val xp = req.toAbsoluteXp() ?: throw badRequest("must supply either `xp` or `level`")
        if (xp < 0) throw badRequest("resulting xp must be >= 0")
        val result = skills.adminSetSkillXp(target, skill.id, xp)
        audit(
            admin,
            "agent.skill_set",
            target,
            buildMap {
                put("skillId", skill.id.value)
                req.xp?.let { put("xp", it) }
                req.level?.let { put("level", it) }
                put("previousXp", result.previousXp)
                put("newXp", result.newXp)
            },
            tickClock.currentTick(),
        )
        return SkillUpdateDto(
            skillId = skill.id.value,
            previousXp = result.previousXp,
            newXp = result.newXp,
            crossedMilestones = result.crossedMilestones,
        )
    }

    @DeleteMapping("/skills/{skillId}")
    fun forceUnequip(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable skillId: String,
    ): ForceUnequipResponse {
        val target = AgentId(agentId)
        requireAgent(target)
        val skill = skillLookup.byId(SkillId(skillId))
            ?: throw notFound("skill '$skillId' is not in the catalog")
        val removed = skills.adminForceUnequip(target, skill.id)
        audit(
            admin,
            "agent.skill_force_unequipped",
            target,
            mapOf("skillId" to skill.id.value, "removed" to removed, "force" to true),
            tickClock.currentTick(),
        )
        return ForceUnequipResponse(
            skillId = skill.id.value,
            removed = removed,
            warning = "Slots are normally permanent; this is a force-unequip and bypasses the project's no-respec rule.",
        )
    }

    @PostMapping("/perks/{perkId}")
    fun grantPerk(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable perkId: String,
    ): PerkUpdateDto {
        val target = AgentId(agentId)
        requireAgent(target)
        val perk = perkLookup.byId(PerkId(perkId))
            ?: throw notFound("perk '$perkId' is not in the catalog")
        val tick = tickClock.currentTick()
        return when (val outcome = perks.recordChoice(target, perk.id, tick)) {
            RecordPerkResult.Recorded -> {
                publisher.publishEvent(
                    AgentEvent.PerkChosen(
                        agent = target,
                        skill = perk.skill,
                        milestone = perk.milestoneLevel,
                        perk = perk.id,
                        tick = tick,
                    ),
                )
                audit(
                    admin,
                    "agent.perk_granted",
                    target,
                    mapOf("perkId" to perk.id.value, "skillId" to perk.skill.value, "milestone" to perk.milestoneLevel),
                    tick,
                )
                PerkUpdateDto(perkId = perk.id.value, skillId = perk.skill.value, milestone = perk.milestoneLevel)
            }
            is RecordPerkResult.MilestoneAlreadyChosen ->
                throw badRequest(
                    "milestone ${outcome.skill.value}@${outcome.milestoneLevel} already has perk ${outcome.existing.value}",
                )
            is RecordPerkResult.UnknownPerk -> throw notFound("perk '${outcome.perk.value}' is not in the catalog")
        }
    }

    @DeleteMapping("/perks/{perkId}")
    fun revokePerk(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable perkId: String,
    ): PerkUpdateDto {
        val target = AgentId(agentId)
        requireAgent(target)
        val perk = perkLookup.byId(PerkId(perkId))
            ?: throw notFound("perk '$perkId' is not in the catalog")
        val removed = perks.adminRevoke(target, perk.id)
        if (!removed) throw notFound("agent does not hold perk '$perkId'")
        audit(
            admin,
            "agent.perk_revoked",
            target,
            mapOf("perkId" to perk.id.value, "skillId" to perk.skill.value, "milestone" to perk.milestoneLevel, "force" to true),
            tickClock.currentTick(),
        )
        return PerkUpdateDto(perkId = perk.id.value, skillId = perk.skill.value, milestone = perk.milestoneLevel)
    }

    @PostMapping("/class")
    fun setClass(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: SetClassRequest,
    ): AgentDetailDto {
        val target = AgentId(agentId)
        val tick = tickClock.currentTick()
        val updated = agents.adminAssignClass(target, req.classId)
            ?: throw notFound("agent $agentId is not registered")
        publisher.publishEvent(AgentEvent.ClassChosen(agent = target, classId = req.classId, tick = tick))
        audit(
            admin,
            "agent.class_set",
            target,
            mapOf("classId" to req.classId.name, "force" to true),
            tick,
        )
        return updated.toDetailDto()
    }

    @PostMapping("/class/offers")
    fun reopenOffers(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
    ): AgentDetailDto {
        val target = AgentId(agentId)
        val before = requireAgent(target)
        val tick = tickClock.currentTick()
        agents.adminClearPendingOffers(target)
        if (before.classId == null) {
            level10.tryEmitFor(target, tick)
        } else if (before.level >= LEVEL_FIFTY_THRESHOLD) {
            level50.tryEmitFor(target, tick)
        }
        audit(
            admin,
            "agent.class_offers_reopened",
            target,
            mapOf("force" to true, "level" to before.level, "hadClass" to (before.classId != null)),
            tick,
        )
        return detail(agentId)
    }

    private fun requireAgent(agentId: AgentId): Agent =
        agents.find(agentId)
            ?: throw notFound("agent ${agentId.id} is not registered")

    private fun audit(admin: Admin, action: String, target: AgentId, payload: Map<String, Any?>, tick: Long) {
        auditLog.record(
            adminId = admin.id,
            action = action,
            target = "agent",
            targetId = target.id.toString(),
            payload = payload,
            tick = tick,
        )
    }

    private fun validateAttributeBounds(req: SetAttributesRequest) {
        val minAttribute = 1
        listOf("str" to req.str, "dex" to req.dex, "con" to req.con, "per" to req.per, "int" to req.int, "luck" to req.luck)
            .forEach { (name, value) ->
                if (value != null && value < minAttribute) {
                    throw badRequest("$name must be >= $minAttribute, got $value")
                }
            }
        if (req.unspent != null && req.unspent < 0) {
            throw badRequest("unspent must be >= 0, got ${req.unspent}")
        }
        val noOp = listOfNotNull(req.str, req.dex, req.con, req.per, req.int, req.luck, req.unspent).isEmpty()
        if (noOp) throw badRequest("at least one field must be supplied")
    }

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)

    private fun Agent.toDetailDto(): AgentDetailDto {
        val body = world.bodyOf(id)
        val location = world.activePositionOf(id) ?: world.locationOf(id)
        return AgentDetailDto(
            agentId = id.id,
            owner = owner.id,
            name = name,
            race = race.value,
            classId = classId,
            level = level,
            xp = XpDto(current = xpCurrent, toNext = xpToNext),
            attributes = toAttributesDto(),
            unspentAttributePoints = unspentAttributePoints,
            gauges = body?.toGaugesDto(),
            location = location?.value,
            safeNode = safeNodes.find(id)?.value,
            tick = world.currentTickFor(id),
            authority = authority,
            fame = fame,
            outlawState = outlawState.name,
            outlawMisconductScore = outlawMisconductScore,
            pendingClassChoice = offeredClasses?.toList() ?: emptyList(),
            pendingEvolutionChoice = offeredEvolutions?.toList() ?: emptyList(),
        )
    }

    private fun Agent.toAttributesDto() = AttributesDto(
        strength = attributes.strength,
        dexterity = attributes.dexterity,
        constitution = attributes.constitution,
        perception = attributes.perception,
        intelligence = attributes.intelligence,
        luck = attributes.luck,
    )

    private fun BodyView.toGaugesDto() = GaugesDto(
        hp = PoolDto(hp, maxHp),
        stamina = PoolDto(stamina, maxStamina),
        mana = PoolDto(mana, maxMana),
        hunger = PoolDto(hunger, maxHunger),
        thirst = PoolDto(thirst, maxThirst),
        sleep = PoolDto(sleep, maxSleep),
    )

    private companion object {
        const val LEVEL_FIFTY_THRESHOLD = 50
    }
}

data class AgentDetailDto(
    val agentId: UUID,
    val owner: UUID,
    val name: String,
    val race: String,
    val classId: AgentClass?,
    val level: Int,
    val xp: XpDto,
    val attributes: AttributesDto,
    val unspentAttributePoints: Int,
    val gauges: GaugesDto?,
    val location: Long?,
    val safeNode: Long?,
    val tick: Long,
    val authority: Int,
    val fame: Int,
    val outlawState: String,
    val outlawMisconductScore: Int,
    val pendingClassChoice: List<AgentClass>,
    val pendingEvolutionChoice: List<AgentClass>,
)

data class XpDto(val current: Int, val toNext: Int)

data class AttributesDto(
    val strength: Int,
    val dexterity: Int,
    val constitution: Int,
    val perception: Int,
    val intelligence: Int,
    val luck: Int,
)

data class GaugesDto(
    val hp: PoolDto,
    val stamina: PoolDto,
    val mana: PoolDto,
    val hunger: PoolDto,
    val thirst: PoolDto,
    val sleep: PoolDto,
)

data class PoolDto(val current: Int, val max: Int)

data class GrantXpRequest(@field:PositiveOrZero val amount: Int)

data class SetLevelRequest(val level: Int)

data class SetAttributesRequest(
    val str: Int? = null,
    val dex: Int? = null,
    val con: Int? = null,
    val per: Int? = null,
    val int: Int? = null,
    val luck: Int? = null,
    val unspent: Int? = null,
)

data class SetSkillRequest(
    @field:PositiveOrZero val xp: Int? = null,
    @field:PositiveOrZero val level: Int? = null,
) {
    fun toAbsoluteXp(): Int? = when {
        xp != null -> xp
        level != null -> level * SKILL_XP_PER_LEVEL
        else -> null
    }

    private companion object {
        const val SKILL_XP_PER_LEVEL = 10
    }
}

data class SkillUpdateDto(
    val skillId: String,
    val previousXp: Int,
    val newXp: Int,
    val crossedMilestones: List<Int>,
)

data class ForceUnequipResponse(
    val skillId: String,
    val removed: Boolean,
    val warning: String,
)

data class PerkUpdateDto(
    val perkId: String,
    val skillId: String,
    val milestone: Int,
)

data class SetClassRequest(val classId: AgentClass)
