package dev.gvart.genesara.api.internal.mcp.tools.trade

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class TradeRespondTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "trade_respond",
        description = "Accept or reject a pending TradeOffer addressed to you. On accept, both " +
            "inventories swap atomically at the resolution tick; on reject, the offer is closed " +
            "with no state change. Both parties must still be on the same node at resolution. " +
            "Rejections: TradeNotFound, TradeNotPending, NotTradeRecipient, " +
            "TradePartnerNotInSameNode, ItemNotInInventory.",
    )
    fun invoke(
        @ToolParam(required = true, description = "TradeId from the trade_offer_received event.")
        tradeId: String,
        @ToolParam(required = true, description = "True to accept and swap inventories; false to reject the offer.")
        accept: Boolean,
        toolContext: ToolContext,
    ): TradeRespondResponse {
        touchActivity(toolContext, activity, "trade_respond")
        val tradeUuid = runCatching { UUID.fromString(tradeId) }.getOrNull()
            ?: return TradeRespondResponse.rejected(
                tradeId = tradeId,
                accept = accept,
                reason = "bad_trade_id",
                detail = "tradeId must be a UUID",
            )
        val agent = AgentContextHolder.current()
        val command = WorldCommand.TradeRespond(
            agent = agent,
            tradeId = tradeUuid,
            accept = accept,
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TradeRespondResponse.queued(command.commandId, appliesAtTick, tradeUuid, accept)
    }
}
