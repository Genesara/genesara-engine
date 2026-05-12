package dev.gvart.genesara.api.internal.mcp.tools.trade

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class TradeOfferTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "trade_offer",
        description = "Send a barter offer to another agent on your current node. The 'offer' map " +
            "lists items you'll give; the 'request' map lists items you want from them. Both maps " +
            "use itemId -> quantity. At least one side must be non-empty. Queues a TradeOffer " +
            "command; the recipient receives a trade_offer_received event with the tradeId. The " +
            "QUEUED ack echoes the tradeId so you can correlate the eventual TradeAccepted / " +
            "TradeRejected outcome. Rejections: CannotTradeWithSelf, TradeOfferEmpty, " +
            "TradePartnerNotInSameNode, ItemNotInInventory, UnknownItem, InsufficientTrust.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Recipient agent UUID. Must be on your current node.")
        recipientId: String,
        @ToolParam(required = true, description = "Items you offer, keyed by itemId -> positive quantity.")
        offer: Map<String, Int>,
        @ToolParam(required = true, description = "Items you request in exchange, keyed by itemId -> positive quantity.")
        request: Map<String, Int>,
        toolContext: ToolContext,
    ): TradeOfferResponse {
        touchActivity(toolContext, activity, "trade_offer")
        val recipientUuid = runCatching { UUID.fromString(recipientId) }.getOrNull()
            ?: return TradeOfferResponse.rejected(
                reason = "bad_recipient_id",
                detail = "recipientId must be a UUID",
            )
        if (offer.size > MAX_ITEMS_PER_SIDE || request.size > MAX_ITEMS_PER_SIDE) {
            return TradeOfferResponse.rejected(
                reason = "offer_too_wide",
                detail = "each side is capped at $MAX_ITEMS_PER_SIDE distinct items",
            )
        }
        val agent = AgentContextHolder.current()
        val command = WorldCommand.TradeOffer(
            agent = agent,
            recipient = AgentId(recipientUuid),
            offered = offer.mapKeys { ItemId(it.key) },
            requested = request.mapKeys { ItemId(it.key) },
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TradeOfferResponse.queued(command.commandId, appliesAtTick, command.tradeId)
    }

    private companion object {
        // Caps the offered/requested map size per side. Generous against legitimate barters
        // (typical loadouts hold far fewer distinct stack types) while bounding the per-tick
        // validation walk + Redis-queue payload size against an over-wide map.
        const val MAX_ITEMS_PER_SIDE = 32
    }
}
