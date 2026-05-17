package dev.gvart.genesara.world

/**
 * The 12 explicit equipment slots an agent can fill.
 *
 * Two-handed weapons are slotted into [MAIN_HAND] and *logically* occupy
 * [OFF_HAND] as well — the off-hand row stays empty in the database, but
 * the equip/unequip reducer rejects any attempt to fill the off-hand while
 * a two-handed item is in main-hand. See `Item.twoHanded`.
 */
enum class EquipSlot {
    HELMET,
    CHEST,
    PANTS,
    BOOTS,
    GLOVES,
    AMULET,
    RING_LEFT,
    RING_RIGHT,
    BRACELET_LEFT,
    BRACELET_RIGHT,
    MAIN_HAND,
    OFF_HAND,
}
