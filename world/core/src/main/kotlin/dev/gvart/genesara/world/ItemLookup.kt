package dev.gvart.genesara.world

interface ItemLookup {
    fun byId(id: ItemId): Item?
    fun all(): List<Item>
}
