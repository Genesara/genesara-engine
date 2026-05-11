package dev.gvart.genesara.world

/** Catalog-level read surface for the [EquipmentSet] registry (ADR-0002). */
interface EquipmentSetLookup {

    fun byId(id: EquipmentSetId): EquipmentSet?

    fun all(): List<EquipmentSet>

    /**
     * Sets that contain [itemId] in their `pieces` list. Used by the bonus
     * aggregator to discover which sets an agent's equipped item contributes
     * to without walking every catalog entry per call.
     */
    fun setsContaining(itemId: ItemId): List<EquipmentSet>
}
