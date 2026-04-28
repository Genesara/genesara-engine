package dev.gvart.genesara.api.internal.mcp.tools.getstatus

import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("Return the agent's full character snapshot: identity, attributes, level/XP, HP/Stamina/Mana, and current location.")
class GetStatusRequest

data class GetStatusResponse(
    val agentId: String,
    val name: String,
    val race: String,
    val level: Int,
    val xp: XpView,
    val attributes: AttributesView,
    val unspentAttributePoints: Int,
    val hp: PoolView,
    val stamina: PoolView,
    val mana: PoolView,
    val location: Long?,
    val tick: Long,
    val activeEffects: List<String> = emptyList(),
)

data class XpView(
    val current: Int,
    val toNext: Int,
)

data class AttributesView(
    val strength: Int,
    val dexterity: Int,
    val constitution: Int,
    val perception: Int,
    val intelligence: Int,
    val luck: Int,
)

data class PoolView(
    val current: Int,
    val max: Int,
)
