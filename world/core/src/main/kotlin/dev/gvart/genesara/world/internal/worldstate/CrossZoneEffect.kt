package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory

/**
 * Typed write surface between zones (ADR 0003 §P4).
 *
 * A reducer in zone X may mutate only its own slice; cross-zone writes go through
 * this sealed hierarchy. Each variant is owned by — and applied by — the *target*
 * zone's effect handler.
 *
 * Effects are coarse-grained "set the whole entity" — pure math helpers in zone
 * public surfaces (BodyMath, CombatMath, …) compute the new entity, and the
 * effect carries it across the boundary. This keeps reducer purity (Q6 P4) and
 * single-hop semantics (Q8 C3): an effect handler mutates its slice + emits
 * external [dev.gvart.genesara.world.events.WorldEvent]s but never emits further
 * effects.
 */
sealed interface CrossZoneEffect {

    // ── BodySlice targets ───────────────────────────────────────────────────

    data class UpdateBody(val agent: AgentId, val body: AgentBody) : CrossZoneEffect
    data class UpdateInventory(val agent: AgentId, val inventory: AgentInventory) : CrossZoneEffect

    // ── CoreSlice targets ───────────────────────────────────────────────────

    data class SetPosition(val agent: AgentId, val node: NodeId) : CrossZoneEffect
    data class RemovePosition(val agent: AgentId) : CrossZoneEffect

    // ── CombatSlice targets ─────────────────────────────────────────────────

    data class IncrementKillStreak(val agent: AgentId, val currentTick: Long, val windowTicks: Long) : CrossZoneEffect
    data class UpdateKillStreak(val agent: AgentId, val streak: AgentKillStreak) : CrossZoneEffect

    // ── EnvironmentSlice targets ────────────────────────────────────────────

    data class UpdateNpc(val npc: Npc) : CrossZoneEffect
    data class RemoveNpc(val npcId: NpcId, val tick: Long) : CrossZoneEffect
    data class AddSpawnedNpc(val npc: Npc) : CrossZoneEffect

    /**
     * Movement-arrival hook: ask the environment zone whether the destination
     * node owes a fresh batch of NPCs (lazy-on-entry spawn). Handled by the
     * applier's lazy-spawn-aware overload, which invokes
     * [dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawnHook]. The effect
     * carries no Npc payload because the spawn decision is data-driven from
     * world balance + cleared-tick state at apply time.
     */
    data class MaybeSpawnLazyNpcs(
        val destination: NodeId,
        val agent: AgentId,
        val tick: Long,
    ) : CrossZoneEffect
}
