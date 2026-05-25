package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Each `@JsonSubTypes.Type.name` below is the wire-format discriminator written to the
 * Redis per-world command queue. The strings are a stable contract: renaming the Kotlin
 * class is fine, changing a discriminator silently corrupts in-flight queues across pods.
 *
 * Subtypes carrying enum fields (e.g. [EnvironmentCommand.BuildStructure.type] →
 * [dev.gvart.genesara.world.BuildingType]) extend the wire contract by their enum constant
 * names — renaming `STORAGE_CHEST` is equivalent to changing a discriminator.
 *
 * The interface is non-sealed by design (ADR 0003 §"Commands & events"): variants are
 * grouped into per-zone sealed sub-hierarchies ([CoreCommand], [BodyCommand],
 * [CombatCommand], [EconomyCommand], [EnvironmentCommand]) so each zone owns its slice.
 * Jackson deserializes via the consolidated `@JsonSubTypes` listing below; the per-zone
 * `@JsonSubTypes` mirrors are forward-prep for Phase 2 module split.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = CoreCommand.SpawnAgent::class, name = "spawn"),
    JsonSubTypes.Type(value = CoreCommand.MoveAgent::class, name = "move"),
    JsonSubTypes.Type(value = CoreCommand.UnspawnAgent::class, name = "unspawn"),
    JsonSubTypes.Type(value = CoreCommand.Say::class, name = "say"),
    JsonSubTypes.Type(value = CoreCommand.SetSafeNode::class, name = "setSafeNode"),
    JsonSubTypes.Type(value = BodyCommand.ConsumeItem::class, name = "consume"),
    JsonSubTypes.Type(value = BodyCommand.Drink::class, name = "drink"),
    JsonSubTypes.Type(value = BodyCommand.RefreshDerivedPools::class, name = "refreshDerivedPools"),
    JsonSubTypes.Type(value = BodyCommand.Pickup::class, name = "pickup"),
    JsonSubTypes.Type(value = BodyCommand.Respawn::class, name = "respawn"),
    JsonSubTypes.Type(value = CombatCommand.AttackTarget::class, name = "attack"),
    JsonSubTypes.Type(value = CombatCommand.UseAbility::class, name = "useAbility"),
    JsonSubTypes.Type(value = CombatCommand.AttackNpc::class, name = "attackNpc"),
    JsonSubTypes.Type(value = CombatCommand.AttackMount::class, name = "attackMount"),
    JsonSubTypes.Type(value = EconomyCommand.Harvest::class, name = "harvest"),
    JsonSubTypes.Type(value = EconomyCommand.CraftItem::class, name = "craft"),
    JsonSubTypes.Type(value = EconomyCommand.Extract::class, name = "extract"),
    JsonSubTypes.Type(value = EconomyCommand.TradeOffer::class, name = "tradeOffer"),
    JsonSubTypes.Type(value = EconomyCommand.TradeRespond::class, name = "tradeRespond"),
    JsonSubTypes.Type(value = EconomyCommand.PlantCrop::class, name = "plantCrop"),
    JsonSubTypes.Type(value = EconomyCommand.TendCrop::class, name = "tendCrop"),
    JsonSubTypes.Type(value = EconomyCommand.HarvestCrop::class, name = "harvestCrop"),
    JsonSubTypes.Type(value = EnvironmentCommand.BuildStructure::class, name = "build"),
    JsonSubTypes.Type(value = EnvironmentCommand.DepositToChest::class, name = "depositToChest"),
    JsonSubTypes.Type(value = EnvironmentCommand.WithdrawFromChest::class, name = "withdrawFromChest"),
    JsonSubTypes.Type(value = EnvironmentCommand.ToggleGate::class, name = "toggleGate"),
    JsonSubTypes.Type(value = EnvironmentCommand.Tame::class, name = "tame"),
    JsonSubTypes.Type(value = EnvironmentCommand.MountTransport::class, name = "mountTransport"),
    JsonSubTypes.Type(value = EnvironmentCommand.DismountTransport::class, name = "dismountTransport"),
    JsonSubTypes.Type(value = EnvironmentCommand.Maintain::class, name = "maintain"),
    JsonSubTypes.Type(value = SocialCommand.PartyInvite::class, name = "partyInvite"),
    JsonSubTypes.Type(value = SocialCommand.PartyRespond::class, name = "partyRespond"),
    JsonSubTypes.Type(value = SocialCommand.LeaveParty::class, name = "leaveParty"),
    JsonSubTypes.Type(value = SocialCommand.KickPartyMember::class, name = "kickPartyMember"),
)
interface WorldCommand {
    val agent: AgentId
    val commandId: UUID
}
