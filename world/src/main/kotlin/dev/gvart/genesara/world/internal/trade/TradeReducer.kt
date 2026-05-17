package dev.gvart.genesara.world.internal.trade

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RelationshipLookup
import dev.gvart.genesara.world.TradeOffer
import dev.gvart.genesara.world.TradeStatus
import dev.gvart.genesara.world.TradeStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import java.util.UUID

internal fun reduceTradeOffer(
    body: BodySlice,
    core: CoreReadView,
    command: WorldCommand.TradeOffer,
    balance: BalanceLookup,
    items: ItemLookup,
    relationships: RelationshipLookup,
    tradeStore: TradeStore,
    buildings: BuildingsLookup,
    passiveAura: PassiveAuraAggregator,
    scaling: LevelScalingAggregator,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    ensure(command.agent != command.recipient) { WorldRejection.CannotTradeWithSelf(command.agent) }
    ensure(command.offered.isNotEmpty() || command.requested.isNotEmpty()) {
        WorldRejection.TradeOfferEmpty(command.agent)
    }
    validatePositive(command.agent, command.offered)
    validatePositive(command.agent, command.requested)

    val offererAt = ensureNotNull(core.positions[command.agent]) { WorldRejection.NotInWorld(command.agent) }
    val recipientAt = ensureNotNull(core.positions[command.recipient]) { WorldRejection.NotInWorld(command.recipient) }
    ensure(offererAt == recipientAt) {
        WorldRejection.TradePartnerNotInSameNode(command.agent, command.recipient, offererAt, recipientAt)
    }

    validateKnown(items, command.offered)
    validateKnown(items, command.requested)
    validateStock(command.agent, body.inventoryOf(command.agent), command.offered)

    val value = command.offered.values.sum() + command.requested.values.sum()
    val baseValueThreshold = balance.trustGateValueThreshold()
    val tradingPostActive = buildings.byNode(offererAt).any {
        it.type == BuildingType.TRADING_POST && it.status == BuildingStatus.ACTIVE
    }
    val barteringAura = passiveAura.bonusFor(command.agent, ScalingEffect.TRUST_GATE_VALUE_BONUS)
    val barteringScalingRate = scaling.bonusFor(command.agent, ScalingEffect.TRUST_GATE_VALUE_BONUS)
    val barteringMultiplier = 1.0 + barteringScalingRate
    val postMultiplier = if (tradingPostActive) 2.0 else 1.0
    val valueThreshold = ((baseValueThreshold + barteringAura) * barteringMultiplier * postMultiplier).toInt()
    if (value > valueThreshold) {
        val score = relationships.scoreBetween(command.agent, command.recipient)
        val required = balance.trustGateRelationshipThreshold()
        ensure(score >= required) {
            WorldRejection.InsufficientTrust(
                offerer = command.agent,
                recipient = command.recipient,
                value = value,
                valueThreshold = valueThreshold,
                relationshipScore = score,
                relationshipThreshold = required,
            )
        }
    }

    tradeStore.create(
        TradeOffer(
            tradeId = command.tradeId,
            offerer = command.agent,
            recipient = command.recipient,
            offered = command.offered,
            requested = command.requested,
            status = TradeStatus.PENDING,
            openedAtTick = tick,
            resolvedAtTick = null,
        )
    )

    val event = WorldEvent.TradeOfferReceived(
        offerer = command.agent,
        recipient = command.recipient,
        tradeId = command.tradeId,
        at = offererAt,
        offered = command.offered,
        requested = command.requested,
        listeners = setOf(command.agent, command.recipient),
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = body, events = listOf(event))
}

/**
 * Transitional wrapper preserving the legacy (WorldState) signature.
 */
internal fun reduceTradeOffer(
    state: WorldState,
    command: WorldCommand.TradeOffer,
    balance: BalanceLookup,
    items: ItemLookup,
    relationships: RelationshipLookup,
    tradeStore: TradeStore,
    buildings: BuildingsLookup,
    passiveAura: PassiveAuraAggregator,
    scaling: LevelScalingAggregator,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> =
    reduceTradeOffer(
        state.body, state.core, command, balance, items, relationships, tradeStore,
        buildings, passiveAura, scaling, tick,
    ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }

internal fun reduceTradeRespond(
    body: BodySlice,
    core: CoreReadView,
    command: WorldCommand.TradeRespond,
    items: ItemLookup,
    tradeStore: TradeStore,
    triggeredPassives: TriggeredPassiveDispatcher,
    progression: SkillProgression,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val offer = ensureNotNull(tradeStore.findPendingForUpdate(command.tradeId)) {
        resolveMissingTrade(tradeStore, command.tradeId)
    }
    ensure(command.agent == offer.recipient) { WorldRejection.NotTradeRecipient(command.agent, command.tradeId) }

    if (!command.accept) {
        check(tradeStore.markResolved(offer.tradeId, TradeStatus.REJECTED, tick)) {
            "trade ${offer.tradeId} was PENDING under forUpdate but markResolved returned false"
        }
        return@either ReducerOutput(
            sliceDelta = body,
            events = listOf(
                WorldEvent.TradeRejected(
                    offerer = offer.offerer,
                    recipient = offer.recipient,
                    tradeId = offer.tradeId,
                    listeners = setOf(offer.offerer, offer.recipient),
                    tick = tick,
                    causedBy = command.commandId,
                ),
            ),
        )
    }

    val offererAt = ensureNotNull(core.positions[offer.offerer]) { WorldRejection.NotInWorld(offer.offerer) }
    val recipientAt = ensureNotNull(core.positions[offer.recipient]) { WorldRejection.NotInWorld(offer.recipient) }
    ensure(offererAt == recipientAt) {
        WorldRejection.TradePartnerNotInSameNode(offer.recipient, offer.offerer, recipientAt, offererAt)
    }

    validateKnown(items, offer.offered)
    validateKnown(items, offer.requested)
    val offererInv = body.inventoryOf(offer.offerer)
    val recipientInv = body.inventoryOf(offer.recipient)
    validateStock(offer.offerer, offererInv, offer.offered)
    validateStock(offer.recipient, recipientInv, offer.requested)

    val nextOfferer = offererInv.removeAll(offer.offered).addAll(offer.requested)
    val nextRecipient = recipientInv.removeAll(offer.requested).addAll(offer.offered)
    val nextBody = body.copy(
        inventories = body.inventories +
            (offer.offerer to nextOfferer) +
            (offer.recipient to nextRecipient),
    )

    check(tradeStore.markResolved(offer.tradeId, TradeStatus.ACCEPTED, tick)) {
        "trade ${offer.tradeId} was PENDING under forUpdate but markResolved returned false"
    }

    val event = WorldEvent.TradeAccepted(
        offerer = offer.offerer,
        recipient = offer.recipient,
        tradeId = offer.tradeId,
        offered = offer.offered,
        requested = offer.requested,
        listeners = setOf(offer.offerer, offer.recipient),
        tick = tick,
        causedBy = command.commandId,
    )
    val recipientTriggered = triggeredPassives.dispatch(
        firer = offer.recipient,
        trigger = TriggeredPassiveTrigger.ON_TRADE_COMPLETED,
        ctx = TriggerContext.None,
        tick = tick,
        causedBy = command.commandId,
    )
    val offererTriggered = triggeredPassives.dispatch(
        firer = offer.offerer,
        trigger = TriggeredPassiveTrigger.ON_TRADE_COMPLETED,
        ctx = TriggerContext.None,
        tick = tick,
        causedBy = command.commandId,
    )
    progression.accrueXp(offer.offerer, BARTERING, delta = 1, tick, command.commandId, agents.find(offer.offerer)?.classId)
    progression.accrueXp(offer.recipient, BARTERING, delta = 1, tick, command.commandId, agents.find(offer.recipient)?.classId)
    ReducerOutput(sliceDelta = nextBody, events = listOf(event) + recipientTriggered + offererTriggered)
}

/**
 * Transitional wrapper preserving the legacy (WorldState) signature.
 */
internal fun reduceTradeRespond(
    state: WorldState,
    command: WorldCommand.TradeRespond,
    items: ItemLookup,
    tradeStore: TradeStore,
    triggeredPassives: TriggeredPassiveDispatcher,
    progression: SkillProgression,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> =
    reduceTradeRespond(
        state.body, state.core, command, items, tradeStore, triggeredPassives, progression, agents, tick,
    ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }

private val BARTERING = SkillId("BARTERING")

private fun Raise<WorldRejection>.validatePositive(agent: AgentId, items: Map<ItemId, Int>) {
    items.values.forEach { qty ->
        ensure(qty > 0) { WorldRejection.NonPositiveQuantity(agent, qty) }
    }
}

private fun Raise<WorldRejection>.validateKnown(items: ItemLookup, stacks: Map<ItemId, Int>) {
    stacks.keys.forEach { id ->
        ensureNotNull(items.byId(id)) { WorldRejection.UnknownItem(id) }
    }
}

private fun Raise<WorldRejection>.validateStock(
    agent: AgentId,
    inventory: AgentInventory,
    needed: Map<ItemId, Int>,
) {
    needed.forEach { (item, qty) ->
        ensure(inventory.quantityOf(item) >= qty) { WorldRejection.ItemNotInInventory(agent, item) }
    }
}

// forUpdate returns null for two distinct cases — row doesn't exist, or row exists
// but its status isn't PENDING. A second status-agnostic read disambiguates so the
// agent's rejection carries the actionable distinction.
private fun resolveMissingTrade(tradeStore: TradeStore, tradeId: UUID): WorldRejection {
    val existing = tradeStore.find(tradeId) ?: return WorldRejection.TradeNotFound(tradeId)
    return WorldRejection.TradeNotPending(tradeId, existing.status)
}
