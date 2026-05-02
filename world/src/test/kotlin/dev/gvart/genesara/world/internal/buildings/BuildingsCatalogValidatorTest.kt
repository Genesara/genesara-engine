package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class BuildingsCatalogValidatorTest {

    private val items = StubItemLookup(setOf("WOOD", "STONE", "CLAY", "COAL"))

    @Test
    fun `accepts the production catalog when every type is present and materials resolve`() {
        val catalog = catalogFromMap(BuildingType.entries.associate { it.name to defProps(it) })

        BuildingsCatalogValidator(catalog, items).validate()
    }

    @Test
    fun `rejects when a BuildingType enum variant is missing from the catalog`() {
        val partial = BuildingType.entries.drop(1).associate { it.name to defProps(it) }
        val catalog = catalogFromMap(partial)

        val ex = assertThrows<IllegalArgumentException> {
            BuildingsCatalogValidator(catalog, items).validate()
        }
        assertTrue(ex.message?.contains(BuildingType.entries.first().name) == true)
    }

    @Test
    fun `rejects materials referencing items not in the catalog`() {
        val catalog = catalogFromMap(
            BuildingType.entries.associate { type ->
                type.name to if (type == BuildingType.CAMPFIRE) {
                    defProps(type).copy(totalMaterials = mapOf("PHANTOM" to 5))
                } else {
                    defProps(type)
                }
            },
        )

        val ex = assertThrows<IllegalArgumentException> {
            BuildingsCatalogValidator(catalog, items).validate()
        }
        assertTrue(ex.message?.contains("PHANTOM") == true)
    }

    @Test
    fun `rejects STORAGE_CHEST without a chestCapacityGrams`() {
        val catalog = catalogFromMap(
            BuildingType.entries.associate { type ->
                type.name to if (type == BuildingType.STORAGE_CHEST) {
                    defProps(type).copy(chestCapacityGrams = null)
                } else {
                    defProps(type)
                }
            },
        )

        val ex = assertThrows<IllegalArgumentException> {
            BuildingsCatalogValidator(catalog, items).validate()
        }
        assertTrue(ex.message?.contains("STORAGE_CHEST") == true)
        assertTrue(ex.message?.contains("chestCapacityGrams") == true)
    }

    @Test
    fun `rejects non-chest types that wrongly carry a chestCapacityGrams`() {
        val catalog = catalogFromMap(
            BuildingType.entries.associate { type ->
                type.name to if (type == BuildingType.WORKBENCH) {
                    defProps(type).copy(chestCapacityGrams = 10_000)
                } else {
                    defProps(type)
                }
            },
        )

        val ex = assertThrows<IllegalArgumentException> {
            BuildingsCatalogValidator(catalog, items).validate()
        }
        assertTrue(ex.message?.contains("WORKBENCH") == true)
    }

    @Test
    fun `catalog itself rejects non-positive totalSteps before the validator sees it`() {
        // Belt-and-suspenders: catalog construction trips first; validator's parallel
        // check is a fence in case a future seam bypasses BuildingsCatalog.
        val ex = assertThrows<IllegalArgumentException> {
            catalogFromMap(mapOf("CAMPFIRE" to defProps(BuildingType.CAMPFIRE).copy(totalSteps = 0)))
        }
        assertTrue(ex.message?.contains("totalSteps") == true)
    }

    private fun catalogFromMap(map: Map<String, BuildingProperties>): BuildingsCatalog =
        BuildingsCatalog(BuildingDefinitionProperties(catalog = map))

    private fun defProps(type: BuildingType): BuildingProperties = BuildingProperties(
        requiredSkill = "SURVIVAL",
        totalSteps = 5,
        staminaPerStep = 8,
        hp = 30,
        categoryHint = BuildingCategoryHint.COOKING,
        totalMaterials = mapOf("WOOD" to 5),
        chestCapacityGrams = if (type == BuildingType.STORAGE_CHEST) 50_000 else null,
    )

    private class StubItemLookup(private val ids: Set<String>) : ItemLookup {
        override fun byId(id: ItemId): Item? =
            if (id.value in ids) {
                Item(
                    id = id,
                    displayName = id.value,
                    description = "",
                    category = ItemCategory.RESOURCE,
                    weightPerUnit = 100,
                    maxStack = 100,
                )
            } else null
        override fun all(): List<Item> = emptyList()
    }
}
