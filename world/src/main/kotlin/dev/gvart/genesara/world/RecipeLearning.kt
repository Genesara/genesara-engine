package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId

/**
 * Single entry point for the per-agent recipe ledger writes triggered by
 * `select_perk` and `consume`. Wraps the gateway + the catalog reverse-index
 * + the [dev.gvart.genesara.player.events.AgentEvent.RecipeLearned] emission
 * so callers don't have to repeat the three-step dance.
 *
 * Idempotent: re-learning an already-known recipe is a no-op and does not
 * re-emit the event.
 */
interface RecipeLearning {

    /**
     * Look up every recipe taught by [perk] in the catalog reverse index, insert any
     * absent rows into the ledger at [tick], and emit `RecipeLearned` once per
     * genuinely-new entry. Returns the recipes that were newly learned (may be empty).
     */
    fun learnFromPerk(agent: AgentId, perk: PerkId, tick: Long): List<RecipeId>

    /**
     * Look up every recipe taught by [item] in the catalog reverse index, insert any
     * absent rows into the ledger at [tick], and emit `RecipeLearned` once per
     * genuinely-new entry. Returns the recipes that were newly learned (may be empty).
     */
    fun learnFromItem(agent: AgentId, item: ItemId, tick: Long): List<RecipeId>

    companion object {
        /** Stub for tests that don't exercise recipe learning. */
        val NoOp: RecipeLearning = object : RecipeLearning {
            override fun learnFromPerk(agent: AgentId, perk: PerkId, tick: Long): List<RecipeId> = emptyList()
            override fun learnFromItem(agent: AgentId, item: ItemId, tick: Long): List<RecipeId> = emptyList()
        }
    }
}
