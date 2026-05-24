package dev.gvart.genesara.api.internal.mcp.tools.trade

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EconomyCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class TradeOfferTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "trade_offer",
        description = "Send a barter offer to another agent on your current node. The 'offer' / " +
            "'request' maps swap stackable items by itemId -> quantity; 'offerInstances' / " +
            "'requestInstances' swap per-instance items (specific equipment, keys, mount gear) " +
            "by instance UUID. At least one bucket must be non-empty. Instance items must be " +
            "unequipped + not stowed on a mount. Queues a TradeOffer command; the recipient " +
            "receives a trade_offer_received event with the tradeId. The QUEUED ack echoes the " +
            "tradeId so you can correlate the eventual TradeAccepted / TradeRejected outcome. " +
            "Rejections: CannotTradeWithSelf, TradeOfferEmpty, TradePartnerNotInSameNode, " +
            "ItemNotInInventory, UnknownItem, InsufficientTrust, TradeInstanceNotFound, " +
            "TradeInstanceNotOwned, TradeInstanceUnavailable.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Recipient agent UUID. Must be on your current node.")
        recipientId: String,
        @ToolParam(required = true, description = "Items you offer, keyed by itemId -> positive quantity.")
        offer: Map<String, Int>,
        @ToolParam(required = true, description = "Items you request in exchange, keyed by itemId -> positive quantity.")
        request: Map<String, Int>,
        @ToolParam(required = false, description = "Per-instance item UUIDs you offer (equipment, keys, mount gear). Each must be unequipped and not stowed in a mount.")
        offerInstances: List<String>?,
        @ToolParam(required = false, description = "Per-instance item UUIDs you request. The recipient must own each one, unequipped and unstowed.")
        requestInstances: List<String>?,
        toolContext: ToolContext,
    ): TradeOfferResponse {
        touchActivity(toolContext, activity, "trade_offer")
        val recipientUuid = runCatching { UUID.fromString(recipientId) }.getOrNull()
            ?: return TradeOfferResponse.rejected(
                reason = "bad_recipient_id",
                detail = "recipientId must be a UUID",
            )
        val offerInstanceList = offerInstances.orEmpty()
        val requestInstanceList = requestInstances.orEmpty()
        if (offer.size > MAX_ITEMS_PER_SIDE || request.size > MAX_ITEMS_PER_SIDE) {
            return TradeOfferResponse.rejected(
                reason = "offer_too_wide",
                detail = "each stackable side is capped at $MAX_ITEMS_PER_SIDE distinct items",
            )
        }
        if (offerInstanceList.size > MAX_INSTANCES_PER_SIDE || requestInstanceList.size > MAX_INSTANCES_PER_SIDE) {
            return TradeOfferResponse.rejected(
                reason = "offer_too_wide",
                detail = "each instance side is capped at $MAX_INSTANCES_PER_SIDE UUIDs",
            )
        }
        val offerInstanceIds = offerInstanceList.parseUuids()
            ?: return TradeOfferResponse.rejected(
                reason = "bad_instance_id",
                detail = "offerInstances must contain valid UUIDs",
            )
        val requestInstanceIds = requestInstanceList.parseUuids()
            ?: return TradeOfferResponse.rejected(
                reason = "bad_instance_id",
                detail = "requestInstances must contain valid UUIDs",
            )
        val agent = AgentContextHolder.current()
        val command = EconomyCommand.TradeOffer(
            agent = agent,
            recipient = AgentId(recipientUuid),
            offered = offer.mapKeys { ItemId(it.key) },
            requested = request.mapKeys { ItemId(it.key) },
            offeredInstances = offerInstanceIds,
            requestedInstances = requestInstanceIds,
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TradeOfferResponse.queued(command.commandId, appliesAtTick, command.tradeId)
    }

    private fun List<String>.parseUuids(): Set<UUID>? {
        if (isEmpty()) return emptySet()
        val parsed = LinkedHashSet<UUID>(size)
        for (raw in this) {
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
            parsed += uuid
        }
        return parsed
    }

    private companion object {
        // Caps the offered/requested map size per side. Generous against legitimate barters
        // (typical loadouts hold far fewer distinct stack types) while bounding the per-tick
        // validation walk + Redis-queue payload size against an over-wide map.
        const val MAX_ITEMS_PER_SIDE = 32

        // Symmetric per-instance cap. An agent's full loadout (12 equip slots + small key
        // ring + a handful of mount gear) sits well under this.
        const val MAX_INSTANCES_PER_SIDE = 32
    }
}
