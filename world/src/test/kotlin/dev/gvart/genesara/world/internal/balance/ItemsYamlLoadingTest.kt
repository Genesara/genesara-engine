package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ItemsYamlLoadingTest {

    @Test
    fun `production items_yaml binds the fauna-expansion raw materials with HUNTING harvest-skill`() {
        loadCatalog().use { lookup ->
            for (id in rawFaunaItems) {
                val item = assertNotNull(lookup.byId(ItemId(id)), "missing raw fauna item: $id")
                assertEquals(ItemCategory.RESOURCE, item.category, "$id category")
                assertEquals(99, item.maxStack, "$id max-stack")
                assertEquals("HUNTING", item.harvestSkill?.value, "$id harvest-skill")
                assertNull(item.consumable, "$id raw material must not be consumable")
            }
        }
    }

    @Test
    fun `production items_yaml binds cooked food items with HUNGER refill consumable`() {
        loadCatalog().use { lookup ->
            val expectedHungerRefills = mapOf(
                "ROAST_MEAT" to 20,
                "MEAT_STEW" to 35,
                "GAME_PIE" to 45,
                "HEARTY_BROTH" to 25,
                "SMOKED_MEAT" to 20,
                "SMOKED_FISH" to 20,
                "JERKY" to 15,
                "VENISON_ROAST" to 60,
                "BONE_MARROW_SOUP" to 30,
            )
            for ((id, refill) in expectedHungerRefills) {
                val item = assertNotNull(lookup.byId(ItemId(id)), "missing food: $id")
                assertEquals(ItemCategory.RESOURCE, item.category, "$id category")
                val effect = assertNotNull(item.consumable, "$id must be consumable")
                assertEquals(Gauge.HUNGER, effect.gauge, "$id gauge")
                assertEquals(refill, effect.amount, "$id refill amount")
                assertEquals(20, item.maxStack, "$id max-stack")
            }
        }
    }

    @Test
    fun `production items_yaml binds brewed drinks with THIRST refill consumable`() {
        loadCatalog().use { lookup ->
            val expectedThirstRefills = mapOf(
                "ALE" to 25,
                "BERRY_WINE" to 30,
                "HERBAL_TONIC" to 20,
                "MEAD" to 35,
                "MUSHROOM_LIQUOR" to 25,
            )
            for ((id, refill) in expectedThirstRefills) {
                val item = assertNotNull(lookup.byId(ItemId(id)), "missing drink: $id")
                assertEquals(ItemCategory.RESOURCE, item.category, "$id category")
                val effect = assertNotNull(item.consumable, "$id must be consumable")
                assertEquals(Gauge.THIRST, effect.gauge, "$id gauge")
                assertEquals(refill, effect.amount, "$id refill amount")
                assertEquals(10, item.maxStack, "$id max-stack")
            }
        }
    }

    @Test
    fun `production items_yaml binds alchemy outputs as inert resources pending status-effect engine`() {
        loadCatalog().use { lookup ->
            for (id in listOf("POISON_VIAL", "ANTIDOTE", "STAMINA_TONIC")) {
                val item = assertNotNull(lookup.byId(ItemId(id)), "missing alchemy item: $id")
                assertNull(item.consumable, "$id ships inert in this slice")
                assertEquals(5, item.maxStack, "$id max-stack")
            }
        }
    }

    @Test
    fun `production items_yaml binds fauna intermediates as non-consumable resources`() {
        loadCatalog().use { lookup ->
            for (id in listOf("HARDENED_LEATHER", "BONE_MEAL", "DRIED_FUR", "VENOM_EXTRACT")) {
                val item = assertNotNull(lookup.byId(ItemId(id)), "missing intermediate: $id")
                assertEquals(ItemCategory.RESOURCE, item.category)
                assertNull(item.consumable)
                assertEquals(99, item.maxStack)
            }
        }
    }

    private val rawFaunaItems = listOf(
        "MEAT", "FUR", "BONE", "FANG", "SINEW",
        "FEATHER", "HORN", "SCALE", "CHITIN", "GLAND", "HONEY",
    )

    private fun loadCatalog(): CatalogHandle {
        val ctx = AnnotationConfigApplicationContext()
        ConfigurationPropertiesBindingPostProcessor.register(ctx)
        ctx.register(ItemBalanceConfiguration::class.java)
        ctx.refresh()
        val lookup = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))
        return CatalogHandle(ctx, lookup)
    }

    private class CatalogHandle(
        private val ctx: AnnotationConfigApplicationContext,
        private val lookup: ItemLookupImpl,
    ) : AutoCloseable {
        fun <R> use(block: (ItemLookupImpl) -> R): R = try {
            block(lookup)
        } finally {
            ctx.close()
        }
        override fun close() = ctx.close()
    }
}
