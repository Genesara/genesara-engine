package dev.gvart.genesara.world

/**
 * The five canonical damage taxonomies used by combat resolution. Weapons declare
 * their primary type via [Item.damageType]; armor pieces will declare per-type
 * resistances when armor reductions land in a later combat slice. Status-effect
 * damage (Bleed/Burn/Poison) carries its own type when those land.
 */
enum class DamageType {
    SLASH,
    PIERCE,
    BLUNT,
    ENERGY,
    MAGICAL,
}
