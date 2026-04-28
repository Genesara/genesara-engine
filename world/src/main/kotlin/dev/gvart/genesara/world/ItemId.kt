package dev.gvart.genesara.world

/**
 * Stable string identifier for an item type; resolved against the YAML item catalog
 * (`world-definition/items.yaml`) at runtime. Stored in `agent_inventory.item_id` and
 * referenced from per-terrain `gatherables` lists.
 */
@JvmInline
value class ItemId(val value: String)
