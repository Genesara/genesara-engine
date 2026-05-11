package dev.gvart.genesara.api.internal.mcp

import dev.gvart.genesara.api.internal.mcp.events.EventLogProperties
import dev.gvart.genesara.api.internal.mcp.jackson.EnumCaseInsensitiveToolCallbackProvider
import dev.gvart.genesara.api.internal.mcp.presence.PresenceProperties
import dev.gvart.genesara.api.internal.mcp.tools.abilities.UseAbilityTool
import dev.gvart.genesara.api.internal.mcp.tools.attack.AttackTool
import dev.gvart.genesara.api.internal.mcp.tools.attributes.AllocatePointsTool
import dev.gvart.genesara.api.internal.mcp.tools.build.BuildTool
import dev.gvart.genesara.api.internal.mcp.tools.chest.DepositToChestTool
import dev.gvart.genesara.api.internal.mcp.tools.chest.WithdrawFromChestTool
import dev.gvart.genesara.api.internal.mcp.tools.classselect.SelectClassTool
import dev.gvart.genesara.api.internal.mcp.tools.classselect.SelectEvolutionTool
import dev.gvart.genesara.api.internal.mcp.tools.consume.ConsumeTool
import dev.gvart.genesara.api.internal.mcp.tools.craft.CraftTool
import dev.gvart.genesara.api.internal.mcp.tools.drink.DrinkTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.EquipItemTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.UnequipSlotTool
import dev.gvart.genesara.api.internal.mcp.tools.getmap.GetMapTool
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.GetStatusTool
import dev.gvart.genesara.api.internal.mcp.tools.harvest.HarvestTool
import dev.gvart.genesara.api.internal.mcp.tools.inspect.InspectTool
import dev.gvart.genesara.api.internal.mcp.tools.loadout.GetLoadoutTool
import dev.gvart.genesara.api.internal.mcp.tools.lookaround.LookAroundTool
import dev.gvart.genesara.api.internal.mcp.tools.move.MoveTool
import dev.gvart.genesara.api.internal.mcp.tools.pickup.PickupTool
import dev.gvart.genesara.api.internal.mcp.tools.respawn.RespawnTool
import dev.gvart.genesara.api.internal.mcp.tools.safenode.SetSafeNodeTool
import dev.gvart.genesara.api.internal.mcp.tools.skills.EquipSkillTool
import dev.gvart.genesara.api.internal.mcp.tools.spawn.SpawnTool
import dev.gvart.genesara.api.internal.mcp.tools.unspawn.UnspawnTool
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
    ): ToolCallbackProvider {
        val methodProvider = MethodToolCallbackProvider.builder()
            .toolObjects(
                spawn, move, lookAround, unspawn, getStatus, harvest, getLoadout,
                consume, drink, equipSkill, allocatePoints, inspect, getMap,
                equipItem, unequipSlot,
                setSafeNode, respawn, build, depositToChest, withdrawFromChest, craft, pickup, attack,
                useAbility, selectClass, selectEvolution,
            )
            .build()
        return EnumCaseInsensitiveToolCallbackProvider(methodProvider)
    }
}
