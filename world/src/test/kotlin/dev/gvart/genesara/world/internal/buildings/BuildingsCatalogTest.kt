package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildingsCatalogTest {

    @Test
    fun `def returns the resolved BuildingDef for a known type`() {
        val catalog = catalogOf(
            "CAMPFIRE" to BuildingProperties(
                requiredSkill = "SURVIVAL",
                totalSteps = 5,
                staminaPerStep = 8,
                hp = 30,
                categoryHint = BuildingCategoryHint.COOKING,
                totalMaterials = mapOf("WOOD" to 10, "STONE" to 5),
            ),
        )

        val def = catalog.def(BuildingType.CAMPFIRE)
        assertEquals(SkillId("SURVIVAL"), def.requiredSkill)
        assertEquals(1, def.requiredSkillLevel)
        assertEquals(5, def.totalSteps)
        assertEquals(8, def.staminaPerStep)
        assertEquals(30, def.hp)
        assertEquals(BuildingCategoryHint.COOKING, def.categoryHint)
        assertEquals(mapOf(ItemId("WOOD") to 10, ItemId("STONE") to 5), def.totalMaterials)
    }

    @Test
    fun `stepMaterials evenly distributes when total divides cleanly`() {
        val catalog = catalogOf(
            "CAMPFIRE" to props(
                totalSteps = 5,
                materials = mapOf("WOOD" to 10),
            ),
        )

        val def = catalog.def(BuildingType.CAMPFIRE)
        assertEquals(List(5) { mapOf(ItemId("WOOD") to 2) }, def.stepMaterials)
    }

    @Test
    fun `stepMaterials concentrates the remainder on the final step`() {
        val catalog = catalogOf(
            "WORKBENCH" to props(
                totalSteps = 10,
                materials = mapOf("WOOD" to 25, "STONE" to 10),
            ),
        )

        val def = catalog.def(BuildingType.WORKBENCH)
        for (i in 0 until 9) {
            assertEquals(mapOf(ItemId("WOOD") to 2, ItemId("STONE") to 1), def.stepMaterials[i], "step $i")
        }
        assertEquals(mapOf(ItemId("WOOD") to 7, ItemId("STONE") to 1), def.stepMaterials[9])

        val sum = def.stepMaterials.flatMap { it.entries }.groupingBy { it.key }
            .fold(0) { acc, e -> acc + e.value }
        assertEquals(def.totalMaterials, sum)
    }

    @Test
    fun `stepMaterials lands every unit on the final step when total is less than steps`() {
        // Critical guard: a naive `total / steps` would silently drop materials and
        // let an agent build for free.
        val catalog = catalogOf(
            "FARM_PLOT" to props(
                totalSteps = 10,
                materials = mapOf("WOOD" to 3),
            ),
        )

        val def = catalog.def(BuildingType.FARM_PLOT)
        for (i in 0 until 9) {
            assertEquals(emptyMap(), def.stepMaterials[i], "step $i")
        }
        assertEquals(mapOf(ItemId("WOOD") to 3), def.stepMaterials[9])
    }

    @Test
    fun `def fails for an unknown type`() {
        val catalog = catalogOf("CAMPFIRE" to props())
        assertFailsWith<IllegalStateException> { catalog.def(BuildingType.SHELTER) }
    }

    @Test
    fun `chestCapacityGrams round-trips through the def`() {
        val catalog = catalogOf(
            "STORAGE_CHEST" to BuildingProperties(
                requiredSkill = "CARPENTRY",
                totalSteps = 8,
                staminaPerStep = 8,
                hp = 40,
                categoryHint = BuildingCategoryHint.STORAGE,
                totalMaterials = mapOf("WOOD" to 20),
                chestCapacityGrams = 50_000,
            ),
        )

        assertEquals(50_000, catalog.def(BuildingType.STORAGE_CHEST).chestCapacityGrams)
    }

    private fun catalogOf(vararg entries: Pair<String, BuildingProperties>): BuildingsCatalog =
        BuildingsCatalog(BuildingDefinitionProperties(catalog = mapOf(*entries)))

    private fun props(
        totalSteps: Int = 5,
        materials: Map<String, Int> = mapOf("WOOD" to 5),
    ): BuildingProperties = BuildingProperties(
        requiredSkill = "SURVIVAL",
        totalSteps = totalSteps,
        staminaPerStep = 8,
        hp = 30,
        categoryHint = BuildingCategoryHint.COOKING,
        totalMaterials = materials,
    )
}
