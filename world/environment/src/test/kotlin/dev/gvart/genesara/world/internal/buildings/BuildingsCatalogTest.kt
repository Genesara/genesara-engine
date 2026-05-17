package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingsCatalogTest {

    @Test
    fun `def returns the resolved BuildingDef for a single-bar type`() {
        val catalog = catalogOf(
            "CAMPFIRE" to BuildingProperties(
                staminaPerStep = 8,
                hp = 30,
                categoryHint = BuildingCategoryHint.COOKING,
                skillBars = mapOf(
                    "SURVIVAL" to BarProperties(
                        steps = 5,
                        materialsPerStep = mapOf("WOOD" to 2, "STONE" to 1),
                    ),
                ),
            ),
        )

        val def = catalog.def(BuildingType.CAMPFIRE)
        assertEquals(8, def.staminaPerStep)
        assertEquals(30, def.hp)
        assertEquals(BuildingCategoryHint.COOKING, def.categoryHint)
        assertEquals(5, def.totalSteps)
        assertTrue(def.isSingleBar)

        val bar = def.defaultBar()
        assertEquals(SkillId("SURVIVAL"), bar.skill)
        assertEquals(0, bar.level)
        assertEquals(5, bar.steps)
        assertEquals(mapOf(ItemId("WOOD") to 2, ItemId("STONE") to 1), bar.materialsPerStep)
    }

    @Test
    fun `def returns the resolved BuildingDef for a multi-bar type`() {
        val catalog = catalogOf(
            "WATCHTOWER" to BuildingProperties(
                staminaPerStep = 12,
                hp = 90,
                categoryHint = BuildingCategoryHint.VISION,
                skillBars = mapOf(
                    "CARPENTRY" to BarProperties(level = 15, steps = 8, materialsPerStep = mapOf("PLANK" to 2)),
                    "SURVIVAL" to BarProperties(level = 10, steps = 6, materialsPerStep = mapOf("STONE" to 3)),
                ),
            ),
        )

        val def = catalog.def(BuildingType.WATCHTOWER)
        assertTrue(!def.isSingleBar)
        assertEquals(14, def.totalSteps)

        val carp = assertNotNull(def.bar(SkillId("CARPENTRY")))
        assertEquals(15, carp.level)
        assertEquals(8, carp.steps)
        assertEquals(mapOf(ItemId("PLANK") to 2), carp.materialsPerStep)

        val surv = assertNotNull(def.bar(SkillId("SURVIVAL")))
        assertEquals(10, surv.level)
        assertEquals(6, surv.steps)
        assertEquals(mapOf(ItemId("STONE") to 3), surv.materialsPerStep)

        assertNull(def.bar(SkillId("ALCHEMY")))
    }

    @Test
    fun `defaultBar fails for a multi-bar building`() {
        val catalog = catalogOf(
            "WATCHTOWER" to BuildingProperties(
                staminaPerStep = 12,
                hp = 90,
                categoryHint = BuildingCategoryHint.VISION,
                skillBars = mapOf(
                    "CARPENTRY" to BarProperties(level = 15, steps = 8, materialsPerStep = mapOf("PLANK" to 2)),
                    "SURVIVAL" to BarProperties(level = 10, steps = 6, materialsPerStep = mapOf("STONE" to 3)),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> { catalog.def(BuildingType.WATCHTOWER).defaultBar() }
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
                staminaPerStep = 8,
                hp = 40,
                categoryHint = BuildingCategoryHint.STORAGE,
                skillBars = mapOf(
                    "CARPENTRY" to BarProperties(steps = 8, materialsPerStep = mapOf("WOOD" to 3)),
                ),
                chestCapacityGrams = 50_000,
            ),
        )

        assertEquals(50_000, catalog.def(BuildingType.STORAGE_CHEST).chestCapacityGrams)
    }

    private fun catalogOf(vararg entries: Pair<String, BuildingProperties>): BuildingsCatalog =
        BuildingsCatalog(BuildingDefinitionProperties(catalog = mapOf(*entries)))

    private fun props(
        steps: Int = 5,
        materials: Map<String, Int> = mapOf("WOOD" to 1),
    ): BuildingProperties = BuildingProperties(
        staminaPerStep = 8,
        hp = 30,
        categoryHint = BuildingCategoryHint.COOKING,
        skillBars = mapOf("SURVIVAL" to BarProperties(steps = steps, materialsPerStep = materials)),
    )
}
