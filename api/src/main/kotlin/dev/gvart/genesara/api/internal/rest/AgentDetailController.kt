package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Skills split out to `/skills` so this payload stays cheap to poll at 1–2s. */
@RestController
@RequestMapping("/api/agents/{agentId}")
internal class AgentDetailController(
    private val owned: OwnedAgentResolver,
    private val world: WorldQueryGateway,
    private val safeNodes: AgentSafeNodeGateway,
) {

    data class AgentDetailResponse(
        val agentId: UUID,
        val name: String,
        val race: String,
        val classId: AgentClass?,
        val level: Int,
        val xp: XpView,
        val attributes: AttributesView,
        val unspentAttributePoints: Int,
        val gauges: GaugesView?,
        val location: Long?,
        val safeNode: Long?,
        val tick: Long,
        val authority: Int,
        val fame: Int,
        val pendingClassChoice: List<AgentClass>,
        val pendingEvolutionChoice: List<AgentClass>,
    )

    data class XpView(val current: Int, val toNext: Int)

    data class AttributesView(
        val strength: Int,
        val dexterity: Int,
        val constitution: Int,
        val perception: Int,
        val intelligence: Int,
        val luck: Int,
    )

    data class GaugesView(
        val hp: PoolView,
        val stamina: PoolView,
        val mana: PoolView,
        val hunger: PoolView,
        val thirst: PoolView,
        val sleep: PoolView,
    )

    data class PoolView(val current: Int, val max: Int)

    @GetMapping
    fun detail(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): AgentDetailResponse {
        val agent = owned.resolve(player, agentId)
        val body = world.bodyOf(agent.id)
        val location = world.activePositionOf(agent.id) ?: world.locationOf(agent.id)
        return AgentDetailResponse(
            agentId = agent.id.id,
            name = agent.name,
            race = agent.race.value,
            classId = agent.classId,
            level = agent.level,
            xp = XpView(current = agent.xpCurrent, toNext = agent.xpToNext),
            attributes = agent.toAttributesView(),
            unspentAttributePoints = agent.unspentAttributePoints,
            gauges = body?.toView(),
            location = location?.value,
            safeNode = safeNodes.find(agent.id)?.value,
            tick = world.currentTickFor(agent.id),
            authority = agent.authority,
            fame = agent.fame,
            pendingClassChoice = agent.offeredClasses?.toList() ?: emptyList(),
            pendingEvolutionChoice = agent.offeredEvolutions?.toList() ?: emptyList(),
        )
    }

    private fun Agent.toAttributesView() = AttributesView(
        strength = attributes.strength,
        dexterity = attributes.dexterity,
        constitution = attributes.constitution,
        perception = attributes.perception,
        intelligence = attributes.intelligence,
        luck = attributes.luck,
    )

    private fun BodyView.toView() = GaugesView(
        hp = PoolView(hp, maxHp),
        stamina = PoolView(stamina, maxStamina),
        mana = PoolView(mana, maxMana),
        hunger = PoolView(hunger, maxHunger),
        thirst = PoolView(thirst, maxThirst),
        sleep = PoolView(sleep, maxSleep),
    )
}
