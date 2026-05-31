package dev.gvart.genesara.api.internal.mcp

import dev.gvart.genesara.api.internal.mcp.events.EventLogProperties
import dev.gvart.genesara.api.internal.mcp.jackson.EnumCaseInsensitiveToolCallbackProvider
import dev.gvart.genesara.api.internal.mcp.presence.PresenceProperties
import dev.gvart.genesara.api.internal.mcp.session.SessionRecoveryRouterFilter
import dev.gvart.genesara.api.internal.mcp.tools.abilities.UseAbilityTool
import dev.gvart.genesara.api.internal.mcp.tools.attack.AttackTool
import dev.gvart.genesara.api.internal.mcp.tools.attributes.AllocatePointsTool
import dev.gvart.genesara.api.internal.mcp.tools.build.BuildTool
import dev.gvart.genesara.api.internal.mcp.tools.chest.DepositToChestTool
import dev.gvart.genesara.api.internal.mcp.tools.chest.WithdrawFromChestTool
import dev.gvart.genesara.api.internal.mcp.tools.clan.CreateClanTool
import dev.gvart.genesara.api.internal.mcp.tools.clan.DissolveClanTool
import dev.gvart.genesara.api.internal.mcp.tools.clan.GetClanStatusTool
import dev.gvart.genesara.api.internal.mcp.tools.clan.LeaveClanTool
import dev.gvart.genesara.api.internal.mcp.tools.clan.TransferClanLeadershipTool
import dev.gvart.genesara.api.internal.mcp.tools.classselect.SelectClassTool
import dev.gvart.genesara.api.internal.mcp.tools.classselect.SelectEvolutionTool
import dev.gvart.genesara.api.internal.mcp.tools.consume.ConsumeTool
import dev.gvart.genesara.api.internal.mcp.tools.craft.CraftTool
import dev.gvart.genesara.api.internal.mcp.tools.drink.DrinkTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.EquipItemTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.UnequipSlotTool
import dev.gvart.genesara.api.internal.mcp.tools.extract.ExtractTool
import dev.gvart.genesara.api.internal.mcp.tools.getmap.GetMapTool
import dev.gvart.genesara.api.internal.mcp.tools.getrecipes.GetRecipesTool
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.GetStatusTool
import dev.gvart.genesara.api.internal.mcp.tools.harvest.HarvestTool
import dev.gvart.genesara.api.internal.mcp.tools.inspect.InspectNpcTool
import dev.gvart.genesara.api.internal.mcp.tools.inspect.InspectTool
import dev.gvart.genesara.api.internal.mcp.tools.loadout.GetLoadoutTool
import dev.gvart.genesara.api.internal.mcp.tools.lookaround.LookAroundTool
import dev.gvart.genesara.api.internal.mcp.tools.move.MoveTool
import dev.gvart.genesara.api.internal.mcp.tools.perks.SelectPerkTool
import dev.gvart.genesara.api.internal.mcp.tools.pickup.PickupTool
import dev.gvart.genesara.api.internal.mcp.tools.relationships.GetRelationshipsTool
import dev.gvart.genesara.api.internal.mcp.tools.respawn.RespawnTool
import dev.gvart.genesara.api.internal.mcp.tools.safenode.SetSafeNodeTool
import dev.gvart.genesara.api.internal.mcp.tools.say.SayTool
import dev.gvart.genesara.api.internal.mcp.tools.togglegate.ToggleGateTool
import dev.gvart.genesara.api.internal.mcp.tools.party.GetPartyTool
import dev.gvart.genesara.api.internal.mcp.tools.party.KickMemberTool
import dev.gvart.genesara.api.internal.mcp.tools.party.LeavePartyTool
import dev.gvart.genesara.api.internal.mcp.tools.party.PartyInviteTool
import dev.gvart.genesara.api.internal.mcp.tools.party.PartyRespondTool
import dev.gvart.genesara.api.internal.mcp.tools.trade.TradeOfferTool
import dev.gvart.genesara.api.internal.mcp.tools.trade.TradeRespondTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.DismountTransportTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.EquipMountGearTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.MaintainTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.MountTransportTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.StoreOnMountTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.TakeFromMountTool
import dev.gvart.genesara.api.internal.mcp.tools.transport.TameTool
import dev.gvart.genesara.api.internal.mcp.tools.events.GetEventsTool
import dev.gvart.genesara.api.internal.mcp.tools.skills.EquipSkillTool
import dev.gvart.genesara.api.internal.mcp.tools.spawn.SpawnTool
import dev.gvart.genesara.api.internal.mcp.tools.unspawn.UnspawnTool
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(PresenceProperties::class, EventLogProperties::class)
internal class McpServerConfiguration {

    @Bean
    internal fun gameTools(
        spawn: SpawnTool,
        move: MoveTool,
        lookAround: LookAroundTool,
        unspawn: UnspawnTool,
        getStatus: GetStatusTool,
        harvest: HarvestTool,
        getLoadout: GetLoadoutTool,
        consume: ConsumeTool,
        drink: DrinkTool,
        equipSkill: EquipSkillTool,
        allocatePoints: AllocatePointsTool,
        inspect: InspectTool,
        getMap: GetMapTool,
        equipItem: EquipItemTool,
        unequipSlot: UnequipSlotTool,
        setSafeNode: SetSafeNodeTool,
        respawn: RespawnTool,
        build: BuildTool,
        depositToChest: DepositToChestTool,
        withdrawFromChest: WithdrawFromChestTool,
        craft: CraftTool,
        pickup: PickupTool,
        attack: AttackTool,
        useAbility: UseAbilityTool,
        selectClass: SelectClassTool,
        selectEvolution: SelectEvolutionTool,
        selectPerk: SelectPerkTool,
        getRecipes: GetRecipesTool,
        say: SayTool,
        tradeOffer: TradeOfferTool,
        tradeRespond: TradeRespondTool,
        toggleGate: ToggleGateTool,
        extract: ExtractTool,
        inspectNpc: InspectNpcTool,
        getRelationships: GetRelationshipsTool,
        equipMountGear: EquipMountGearTool,
        tame: TameTool,
        mountTransport: MountTransportTool,
        dismountTransport: DismountTransportTool,
        maintain: MaintainTool,
        storeOnMount: StoreOnMountTool,
        takeFromMount: TakeFromMountTool,
        partyInvite: PartyInviteTool,
        partyRespond: PartyRespondTool,
        leaveParty: LeavePartyTool,
        kickMember: KickMemberTool,
        getParty: GetPartyTool,
        createClan: CreateClanTool,
        leaveClan: LeaveClanTool,
        dissolveClan: DissolveClanTool,
        transferClanLeadership: TransferClanLeadershipTool,
        getClanStatus: GetClanStatusTool,
        getEvents: GetEventsTool,
    ): ToolCallbackProvider {
        val methodProvider = MethodToolCallbackProvider.builder()
            .toolObjects(
                spawn, move, lookAround, unspawn, getStatus, harvest, getLoadout,
                consume, drink, equipSkill, allocatePoints, inspect, getMap,
                equipItem, unequipSlot,
                setSafeNode, respawn, build, depositToChest, withdrawFromChest, craft, pickup, attack,
                useAbility, selectClass, selectEvolution, selectPerk, getRecipes, say,
                tradeOffer, tradeRespond,
                toggleGate, extract,
                inspectNpc,
                getRelationships,
                equipMountGear,
                tame, mountTransport, dismountTransport, maintain,
                storeOnMount, takeFromMount,
                partyInvite, partyRespond, leaveParty, kickMember, getParty,
                createClan, leaveClan, dissolveClan, transferClanLeadership, getClanStatus,
                getEvents,
            )
            .build()
        return EnumCaseInsensitiveToolCallbackProvider(methodProvider)
    }

    @Bean
    internal fun webMvcStreamableServerRouterFunction(
        provider: WebMvcStreamableServerTransportProvider,
        mapper: ObjectMapper,
    ): RouterFunction<ServerResponse> =
        provider.routerFunction.filter(SessionRecoveryRouterFilter(mapper))
}
