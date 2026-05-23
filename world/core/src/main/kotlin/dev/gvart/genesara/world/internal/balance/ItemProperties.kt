package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.Rarity

data class ItemProperties(
    val displayName: String,
    val description: String = "",
    val category: ItemCategory = ItemCategory.RESOURCE,
    val weightPerUnit: Int = 0,
    val maxStack: Int = Int.MAX_VALUE,
    /**
     * Effect of consuming one unit. `null` for non-consumables — most raw resources
     * (WOOD, STONE, ORE) feed crafting only.
     */
    val consumable: ConsumableEffectProperties? = null,
    /** Whether depleted deposits regenerate. Default true. */
    val regenerating: Boolean = true,
    /** Ticks between regen events. 0 disables regen even if [regenerating] is true. */
    val regenIntervalTicks: Int = 0,
    /** Quantity added per regen interval. */
    val regenAmount: Int = 0,
    /** Skill id (from `:player`'s catalog) trained on a harvest. Null for non-harvestables. */
    val harvestSkill: String? = null,
    /** Default rarity for instances of this item; defaults to [Rarity.COMMON]. */
    val rarity: Rarity = Rarity.COMMON,
    /** Max durability for instances of this item. Null = no durability (stackable). */
    val maxDurability: Int? = null,
    /** Equipment slots an instance of this item can occupy. Empty for non-equipment. */
    val validSlots: Set<EquipSlot> = emptySet(),
    /** Two-handed weapon flag. Locks the off-hand slot when equipped to MAIN_HAND. */
    val twoHanded: Boolean = false,
    /** Attribute floors required to equip; empty when no prerequisites. */
    val requiredAttributes: Map<Attribute, Int> = emptyMap(),
    /** Skill-level floors required to equip; keys are skill ids; empty when none. */
    val requiredSkills: Map<String, Int> = emptyMap(),
    /** Combat damage taxonomy. Null for non-weapons and weapons not yet combat-wired. */
    val damageType: DamageType? = null,
    /** Multiplier against attacker's combat stat to compute base damage. Null for non-weapons. */
    val weaponPower: Int? = null,
    /** Skill id trained on a successful attack with this weapon. Null = no-XP weapon. */
    val combatSkill: String? = null,
    /** Max node-hop reach of the weapon. 1 = melee (same node). Null = non-weapon. */
    val range: Int? = null,
    /**
     * Heterogeneous wearer-bonus list. Each entry is `{ target: STRING, magnitude: INT }`
     * where `target` is one of [DamageType] (→ ArmorDef), [Attribute] (→ AttributeBonus),
     * or [dev.gvart.genesara.player.ScalingEffect] (→ PassiveBuff). Unknown targets fail
     * app boot in the binder. See ADR-0001.
     */
    val bonuses: List<EquippedBonusProperties> = emptyList(),
    /**
     * Mirrors [dev.gvart.genesara.world.Item.extractionOnly]: when true the
     * `harvest` verb rejects this item and the `extract` verb requires an
     * active MINE on the agent's node.
     */
    val extractionOnly: Boolean = false,
    /** Mount slots this item occupies; empty for non-mount-gear. */
    val mountSlots: Set<MountSlot> = emptySet(),
    /** Maintenance metadata for `maintain`; null for non-maintenance resources. */
    val maintenance: ItemMaintenanceProperties? = null,
    /** Slot-implied bonus magnitude for mount gear; zero for non-mount-gear. */
    val mountGearBonus: Int = 0,
)

data class EquippedBonusProperties(
    val target: String,
    val magnitude: Int,
)

data class ConsumableEffectProperties(
    val gauge: Gauge,
    val amount: Int,
)

data class ItemMaintenanceProperties(
    val type: MaintenanceType,
    val value: Int,
)
