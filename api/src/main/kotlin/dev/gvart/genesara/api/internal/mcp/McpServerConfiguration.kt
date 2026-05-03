package dev.gvart.genesara.api.internal.mcp

import dev.gvart.genesara.api.internal.mcp.events.EventLogProperties
import dev.gvart.genesara.api.internal.mcp.presence.PresenceProperties
import dev.gvart.genesara.api.internal.mcp.tools.build.BuildTool
import dev.gvart.genesara.api.internal.mcp.tools.consume.ConsumeTool
import dev.gvart.genesara.api.internal.mcp.tools.drink.DrinkTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.EquipItemTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.GetEquipmentTool
import dev.gvart.genesara.api.internal.mcp.tools.equipment.UnequipSlotTool
import dev.gvart.genesara.api.internal.mcp.tools.gather.GatherTool
import dev.gvart.genesara.api.internal.mcp.tools.getmap.GetMapTool
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.GetStatusTool
import dev.gvart.genesara.api.internal.mcp.tools.inspect.InspectTool
import dev.gvart.genesara.api.internal.mcp.tools.inventory.GetInventoryTool
import dev.gvart.genesara.api.internal.mcp.tools.lookaround.LookAroundTool
import dev.gvart.genesara.api.internal.mcp.tools.mine.MineTool
import dev.gvart.genesara.api.internal.mcp.tools.move.MoveTool
import dev.gvart.genesara.api.internal.mcp.tools.respawn.RespawnTool
import dev.gvart.genesara.api.internal.mcp.tools.safenode.SetSafeNodeTool
import dev.gvart.genesara.api.internal.mcp.tools.skills.EquipSkillTool
import dev.gvart.genesara.api.internal.mcp.tools.skills.GetSkillsTool
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
        gather: GatherTool,
        mine: MineTool,
        getInventory: GetInventoryTool,
        consume: ConsumeTool,
        drink: DrinkTool,
        getSkills: GetSkillsTool,
        equipSkill: EquipSkillTool,
        inspect: InspectTool,
        getMap: GetMapTool,
        equipItem: EquipItemTool,
        unequipSlot: UnequipSlotTool,
        getEquipment: GetEquipmentTool,
        setSafeNode: SetSafeNodeTool,
        respawn: RespawnTool,
        build: BuildTool,
    ): ToolCallbackProvider =
        MethodToolCallbackProvider.builder()
            .toolObjects(
                spawn, move, lookAround, unspawn, getStatus, gather, mine, getInventory,
                consume, drink, getSkills, equipSkill, inspect, getMap,
                equipItem, unequipSlot, getEquipment,
                setSafeNode, respawn, build,
            )
            .build()
}
