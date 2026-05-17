package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import java.util.UUID

sealed interface CombatEvent : WorldEvent {

    /**
     * Outcome of one [dev.gvart.genesara.world.commands.CombatCommand.AttackTarget].
     * Always emitted on a successful attack reducer run, including when the target
     * dodged ([hpLost] = 0). [targetKilled] is a convenience: a paired
     * [dev.gvart.genesara.world.events.BodyEvent.AgentDied] event lands at the same tick
     * when true. Routed to BOTH attacker and target streams by the dispatcher so each
     * can correlate.
     */
    data class AgentAttacked(
        val attacker: AgentId,
        val target: AgentId,
        val at: NodeId,
        val damageType: DamageType,
        /**
         * Damage after attacker scaling but before crit, dodge, and armor mitigation:
         * `weaponPower × stat × damageTypeMod × (1 + skill scaling bonus)`. Armor will
         * subtract from this in a future combat slice.
         */
        val baseDamage: Int,
        /** Actual HP subtracted from the target. 0 on dodge. */
        val hpLost: Int,
        val isCrit: Boolean,
        val isDodged: Boolean,
        val targetHpAfter: Int,
        val targetKilled: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : CombatEvent

    /** `target` is set only for combat triggers (OnHit*, OnCrit, OnKill, OnDodge). */
    data class PerkTriggered(
        val agent: AgentId,
        val perkId: PerkId,
        val trigger: TriggeredPassiveTrigger,
        val effectKind: TriggeredPassiveEffectKind,
        val params: Map<String, String>,
        val target: AgentId?,
        override val tick: Long,
        val causedBy: UUID?,
    ) : CombatEvent

    /**
     * Outcome of a successful [dev.gvart.genesara.world.commands.CombatCommand.UseAbility].
     * The perk cooldown is now armed until [readyAtTick]; for `SCALE_NEXT_ATTACK`
     * the buff lives on the agent until consumed by the next AttackTarget reducer.
     */
    data class AbilityUsed(
        val agent: AgentId,
        val perkId: PerkId,
        val abilityId: AbilityId,
        val target: AgentId?,
        val effectKind: AbilityEffectKind,
        val params: Map<String, String>,
        val costResource: AbilityCostResource,
        val costAmount: Int,
        val readyAtTick: Long,
        override val tick: Long,
        val causedBy: UUID,
    ) : CombatEvent

    /**
     * NPC AI sweep fired an attack at [target]. Mirrors [AgentAttacked] for
     * agent-vs-NPC observability — the deferral note in PR #N tracks unifying
     * these via `CombatantAttacked`.
     */
    data class NpcAttackedAgent(
        val npc: NpcId,
        val npcType: NpcType,
        val target: AgentId,
        val at: NodeId,
        val damageType: DamageType,
        val baseDamage: Int,
        val hpLost: Int,
        val isDodged: Boolean,
        val targetHpAfter: Int,
        val targetKilled: Boolean,
        override val tick: Long,
    ) : CombatEvent

    /**
     * Agent attacked a Tier-A NPC via the consolidated `attack` MCP tool
     * (npc-prefixed target). Carries the full damage record for the attacker's
     * stream and any spectator in the vision radius of either combatant's node.
     */
    data class AgentAttackedNpc(
        val attacker: AgentId,
        val npc: NpcId,
        val npcType: NpcType,
        val at: NodeId,
        val damageType: DamageType,
        val baseDamage: Int,
        val hpLost: Int,
        val isCrit: Boolean,
        val isDodged: Boolean,
        val npcHpAfter: Int,
        val npcKilled: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : CombatEvent
}
