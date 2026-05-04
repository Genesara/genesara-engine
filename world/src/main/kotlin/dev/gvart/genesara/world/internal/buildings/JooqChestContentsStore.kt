package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.jooq.tables.references.BUILDING_CHEST_INVENTORY
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqChestContentsStore(
    private val dsl: DSLContext,
) : ChestContentsStore {

    @Transactional(readOnly = true)
    override fun quantityOf(buildingId: UUID, item: ItemId): Int =
        dsl.select(BUILDING_CHEST_INVENTORY.QUANTITY)
            .from(BUILDING_CHEST_INVENTORY)
            .where(BUILDING_CHEST_INVENTORY.BUILDING_ID.eq(buildingId))
            .and(BUILDING_CHEST_INVENTORY.ITEM_ID.eq(item.value))
            .fetchOne(BUILDING_CHEST_INVENTORY.QUANTITY)
            ?: 0

    @Transactional(readOnly = true)
    override fun contentsOf(buildingId: UUID): Map<ItemId, Int> =
        dsl.selectFrom(BUILDING_CHEST_INVENTORY)
            .where(BUILDING_CHEST_INVENTORY.BUILDING_ID.eq(buildingId))
            .fetch()
            .associate { ItemId(it.itemId) to it.quantity }

    @Transactional
    override fun add(buildingId: UUID, item: ItemId, quantity: Int) {
        require(quantity > 0) { "quantity to add must be positive, got $quantity" }
        // Postgres UPSERT — atomic insert-or-increment so two concurrent deposits
        // can't read-modify-write past each other.
        dsl.insertInto(BUILDING_CHEST_INVENTORY)
            .set(BUILDING_CHEST_INVENTORY.BUILDING_ID, buildingId)
            .set(BUILDING_CHEST_INVENTORY.ITEM_ID, item.value)
            .set(BUILDING_CHEST_INVENTORY.QUANTITY, quantity)
            .onConflict(BUILDING_CHEST_INVENTORY.BUILDING_ID, BUILDING_CHEST_INVENTORY.ITEM_ID)
            .doUpdate()
            .set(
                BUILDING_CHEST_INVENTORY.QUANTITY,
                BUILDING_CHEST_INVENTORY.QUANTITY.plus(quantity),
            )
            .execute()
    }

    @Transactional
    override fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean {
        require(quantity > 0) { "quantity to remove must be positive, got $quantity" }
        // Two-pass under @Transactional. Delete-on-exact-match first to avoid
        // tripping the QUANTITY > 0 CHECK that would fire on a decrement-to-zero
        // UPDATE; otherwise decrement only when there's strictly more than asked.
        // Each statement is atomic; the surrounding transaction provides the
        // read-modify-write isolation against concurrent removes.
        val deleted = dsl.deleteFrom(BUILDING_CHEST_INVENTORY)
            .where(BUILDING_CHEST_INVENTORY.BUILDING_ID.eq(buildingId))
            .and(BUILDING_CHEST_INVENTORY.ITEM_ID.eq(item.value))
            .and(BUILDING_CHEST_INVENTORY.QUANTITY.eq(quantity))
            .execute()
        if (deleted > 0) return true

        val updated = dsl.update(BUILDING_CHEST_INVENTORY)
            .set(BUILDING_CHEST_INVENTORY.QUANTITY, BUILDING_CHEST_INVENTORY.QUANTITY.minus(quantity))
            .where(BUILDING_CHEST_INVENTORY.BUILDING_ID.eq(buildingId))
            .and(BUILDING_CHEST_INVENTORY.ITEM_ID.eq(item.value))
            .and(BUILDING_CHEST_INVENTORY.QUANTITY.gt(quantity))
            .execute()
        return updated > 0
    }
}
