package dev.gvart.genesara.api.internal.mcp.tools.craft

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Craft an item from a known recipe at the agent's current node. Requires a matching " +
        "crafting station active on the node (forge / workbench / alchemy table), the recipe's " +
        "input materials in inventory, the recipe's required skill level, and stamina. Output " +
        "rarity is rolled per craft from skill + Luck — same recipe + materials can produce " +
        "different qualities. Equipment outputs are signed with the calling agent's id (visible " +
        "on `inspect`). Stackable outputs (potions, intermediates) are unsigned and add directly " +
        "to inventory.",
)
data class CraftRequest(
    @JsonPropertyDescription(
        "Recipe id from the catalog (e.g. RUSTY_SWORD_BASIC, IRON_SWORD_BASIC, IRON_INGOT_BASIC, " +
            "HEALTH_POTION_BASIC, LEATHER_BASIC). Unknown ids are rejected on the event stream.",
    )
    val recipeId: String,
)

/**
 * Successful queue-and-ack response for `craft`. The matching `ItemCrafted` event arrives
 * on the agent's event stream once the tick lands. Reducer-level rejections (unknown
 * recipe, missing station, low skill, missing materials, low stamina, full stack) land
 * on the same stream as `command.rejected` events keyed by `causedBy = commandId`.
 */
data class CraftResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
    val recipeId: String,
)
