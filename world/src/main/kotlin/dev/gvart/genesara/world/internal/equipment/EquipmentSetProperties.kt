package dev.gvart.genesara.world.internal.equipment

internal data class EquipmentSetProperties(
    val pieces: List<String>,
    /** Map<tierCount, threshold> — tier 2/4/6/etc → bonuses added at that tier. */
    val thresholds: Map<Int, EquipmentSetThresholdProperties>,
)

internal data class EquipmentSetThresholdProperties(
    val bonuses: List<dev.gvart.genesara.world.internal.balance.EquippedBonusProperties> = emptyList(),
)
