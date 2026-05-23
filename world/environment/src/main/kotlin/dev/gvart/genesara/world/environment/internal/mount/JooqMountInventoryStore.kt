package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInventoryStore
import dev.gvart.genesara.world.internal.jooq.tables.references.MOUNT_INVENTORY
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqMountInventoryStore(
    private val dsl: DSLContext,
) : MountInventoryStore {

    @Transactional(readOnly = true)
    override fun byMount(mountId: MountId): Map<ItemId, Int> =
        dsl.select(MOUNT_INVENTORY.ITEM_ID, MOUNT_INVENTORY.QUANTITY)
            .from(MOUNT_INVENTORY)
            .where(MOUNT_INVENTORY.MOUNT_ID.eq(mountId.value))
            .fetch()
            .associate { ItemId(it[MOUNT_INVENTORY.ITEM_ID]!!) to it[MOUNT_INVENTORY.QUANTITY]!! }

    @Transactional
    override fun increment(mountId: MountId, itemId: ItemId, quantity: Int) {
        require(quantity > 0) { "increment quantity must be positive, was $quantity" }
        dsl.insertInto(MOUNT_INVENTORY)
            .set(MOUNT_INVENTORY.MOUNT_ID, mountId.value)
            .set(MOUNT_INVENTORY.ITEM_ID, itemId.value)
            .set(MOUNT_INVENTORY.QUANTITY, quantity)
            .onConflict(MOUNT_INVENTORY.MOUNT_ID, MOUNT_INVENTORY.ITEM_ID)
            .doUpdate()
            .set(MOUNT_INVENTORY.QUANTITY, MOUNT_INVENTORY.QUANTITY.plus(quantity))
            .execute()
    }

    @Transactional
    override fun decrement(mountId: MountId, itemId: ItemId, quantity: Int): Boolean {
        require(quantity > 0) { "decrement quantity must be positive, was $quantity" }
        val deleted = dsl.deleteFrom(MOUNT_INVENTORY)
            .where(MOUNT_INVENTORY.MOUNT_ID.eq(mountId.value))
            .and(MOUNT_INVENTORY.ITEM_ID.eq(itemId.value))
            .and(MOUNT_INVENTORY.QUANTITY.eq(quantity))
            .execute()
        if (deleted > 0) return true
        return dsl.update(MOUNT_INVENTORY)
            .set(MOUNT_INVENTORY.QUANTITY, MOUNT_INVENTORY.QUANTITY.minus(quantity))
            .where(MOUNT_INVENTORY.MOUNT_ID.eq(mountId.value))
            .and(MOUNT_INVENTORY.ITEM_ID.eq(itemId.value))
            .and(MOUNT_INVENTORY.QUANTITY.greaterOrEqual(quantity))
            .execute() > 0
    }

    @Transactional
    override fun deleteAllFor(mountId: MountId) {
        dsl.deleteFrom(MOUNT_INVENTORY)
            .where(MOUNT_INVENTORY.MOUNT_ID.eq(mountId.value))
            .execute()
    }

    @Transactional(readOnly = true)
    override fun byMounts(mountIds: Collection<MountId>): Map<MountId, Map<ItemId, Int>> {
        if (mountIds.isEmpty()) return emptyMap()
        return dsl.select(MOUNT_INVENTORY.MOUNT_ID, MOUNT_INVENTORY.ITEM_ID, MOUNT_INVENTORY.QUANTITY)
            .from(MOUNT_INVENTORY)
            .where(MOUNT_INVENTORY.MOUNT_ID.`in`(mountIds.map { it.value }))
            .fetch()
            .groupBy({ MountId(it[MOUNT_INVENTORY.MOUNT_ID]!!) }) {
                ItemId(it[MOUNT_INVENTORY.ITEM_ID]!!) to it[MOUNT_INVENTORY.QUANTITY]!!
            }
            .mapValues { (_, pairs) -> pairs.toMap() }
    }
}
