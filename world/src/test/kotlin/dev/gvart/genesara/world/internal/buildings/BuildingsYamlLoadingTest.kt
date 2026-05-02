package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BuildingsYamlLoadingTest {

    @Test
    fun `production buildings_yaml binds cleanly into the catalog with every BuildingType`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(BuildingsConfiguration::class.java)
            ctx.refresh()

            val props = ctx.getBean(BuildingDefinitionProperties::class.java)
            val catalog = BuildingsCatalog(props)

            assertEquals(BuildingType.entries.toSet(), catalog.all().map { it.type }.toSet())

            val storageChest = assertNotNull(catalog.def(BuildingType.STORAGE_CHEST))
            assertEquals(BuildingCategoryHint.STORAGE, storageChest.categoryHint)
            assertNotNull(storageChest.chestCapacityGrams).also { assertEquals(true, it > 0) }

            for (def in catalog.all()) {
                val summed = def.stepMaterials
                    .flatMap { it.entries }
                    .groupingBy { it.key }
                    .fold(0) { acc, e -> acc + e.value }
                assertEquals(def.totalMaterials, summed, "step totals must sum to total for ${def.type}")
            }
        }
    }
}
