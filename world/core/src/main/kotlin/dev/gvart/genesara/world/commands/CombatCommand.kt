package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.NpcId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = CombatCommand.AttackTarget::class, name = "attack"),
    JsonSubTypes.Type(value = CombatCommand.UseAbility::class, name = "useAbility"),
    JsonSubTypes.Type(value = CombatCommand.AttackNpc::class, name = "attackNpc"),
    JsonSubTypes.Type(value = CombatCommand.AttackMount::class, name = "attackMount"),
)
sealed interface CombatCommand : WorldCommand {

    /**
     * Single melee/ranged attack. The reducer reads the attacker's MAIN_HAND
     * (or unarmed defaults), rolls dodge then crit, applies the resulting damage
     * to [target]'s HP, spends stamina on the attacker, and grants weapon-skill
     * XP. A killing blow flows through [dev.gvart.genesara.world.internal.death.DeathProcessor]
     * inline so [dev.gvart.genesara.world.events.BodyEvent.AgentDied.causedBy] carries this
     * command's [commandId]. No `ability` parameter in Slice 1 — abilities + cooldowns
     * land in a later combat slice.
     */
    data class AttackTarget(
        override val agent: AgentId,
        val target: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CombatCommand

    /**
     * Cast an active ability granted by a chosen perk on a slotted skill. The
     * [target] is required when the ability's `AbilityTarget` is `SINGLE_AGENT`,
     * forbidden when `SELF` / `AREA_SELF_NODE`. The reducer pays the resource
     * cost at cast and arms the perk cooldown; the effect resolves at this same
     * tick (a `ScaleNextAttack` buff lands on the agent and is consumed by the
     * next [AttackTarget] reducer call).
     */
    data class UseAbility(
        override val agent: AgentId,
        val ability: AbilityId,
        val target: AgentId? = null,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CombatCommand

    /**
     * Single attack against a Tier-A NPC. Mirrors [AttackTarget] for the
     * agent-vs-NPC path: reads the attacker's MAIN_HAND weapon, rolls dodge
     * (no NPC crit; agent crit applies), applies typed damage minus the
     * NPC catalog's flat defense, spends stamina, and trains weapon skill.
     * On the killing blow, runs the loot table for the NPC type and deposits
     * drops to the ground.
     */
    data class AttackNpc(
        override val agent: AgentId,
        val npc: NpcId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CombatCommand

    /**
     * Single attack against a Mount. Mirrors [AttackNpc] but the defender's
     * mitigation reads `MountDef.defense + BARDING mountGearBonus`. On the
     * killing blow: `MountDied(cause = COMBAT, killedBy = agent)`, equipped
     * MountGear deleted (permadeath, §16 canon), cargo drops on the ground at
     * the mount's node, the rider (if any) is dismounted.
     */
    data class AttackMount(
        override val agent: AgentId,
        val mount: MountId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CombatCommand
}
