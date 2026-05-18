package dev.gvart.genesara.api.internal.mcp.tools.getrecipes

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLearnSource
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.RecipeUnlockMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetRecipesToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val carpentry = SkillId("CARPENTRY")
    private val smithing = SkillId("SMITHING")
    private val activity = AgentActivityRegistry(MutableTestClock(Instant.parse("2026-01-01T00:00:00Z")))
    private val toolContext = ToolContext(emptyMap())

    private val plank = ItemId("WOODEN_PLANK")
    private val sword = ItemId("IRON_SWORD")
    private val potion = ItemId("HEALTH_POTION")

    private val openRecipe = recipe(
        "PLANK_BASIC",
        output = plank,
        skill = carpentry,
        requiredLevel = 0,
        unlock = RecipeUnlockMode.Open,
    )
    private val skillGatedOpen = recipe(
        "PLANK_ADVANCED",
        output = plank,
        skill = carpentry,
        requiredLevel = 30,
        unlock = RecipeUnlockMode.Open,
    )
    private val perkLocked = recipe(
        "IRON_SWORD_LEGENDARY",
        output = sword,
        skill = smithing,
        requiredLevel = 0,
        unlock = RecipeUnlockMode.ClassPerk(PerkId("SMITHING_FORGE_MASTER")),
    )
    private val itemLearned = recipe(
        "HEALTH_POTION_GREATER",
        output = potion,
        skill = carpentry,
        requiredLevel = 0,
        unlock = RecipeUnlockMode.ItemLearned(ItemId("RECIPE_SCROLL")),
    )

    private val items = StubItems(mapOf(
        plank to itemFor(plank, "Wooden Plank"),
        sword to itemFor(sword, "Iron Sword"),
        potion to itemFor(potion, "Health Potion"),
    ))
    private val recipes = StubRecipes(listOf(openRecipe, skillGatedOpen, perkLocked, itemLearned))

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `open recipes always visible regardless of ledger`() {
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val response = tool.invoke(toolContext)

        val ids = response.recipes.map { it.recipeId }
        assertTrue(openRecipe.id.value in ids)
        assertTrue(perkLocked.id.value !in ids)
        assertTrue(itemLearned.id.value !in ids)
    }

    @Test
    fun `skill-gated open recipes hide until level threshold met`() {
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 10), StubLedger(emptySet()), activity)

        val below = tool.invoke(toolContext).recipes.map { it.recipeId }
        assertTrue(skillGatedOpen.id.value !in below)

        val tool2 = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 35), StubLedger(emptySet()), activity)
        val above = tool2.invoke(toolContext).recipes.map { it.recipeId }
        assertTrue(skillGatedOpen.id.value in above)
    }

    @Test
    fun `class-perk and item-learned recipes appear only when in the ledger`() {
        val ledger = StubLedger(setOf(perkLocked.id))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), ledger, activity)

        val ids = tool.invoke(toolContext).recipes.map { it.recipeId }
        assertTrue(perkLocked.id.value in ids)
        assertTrue(itemLearned.id.value !in ids)
    }

    @Test
    fun `recipes are sorted by id ascending`() {
        val ledger = StubLedger(setOf(perkLocked.id, itemLearned.id))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 50), ledger, activity)

        val ids = tool.invoke(toolContext).recipes.map { it.recipeId }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `equipmentStats is null for resource outputs`() {
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val view = tool.invoke(toolContext).recipes.first { it.recipeId == openRecipe.id.value }
        assertNull(view.output.equipmentStats)
    }

    @Test
    fun `resource outputs project category, weight, and maxStack — no equipmentStats`() {
        val plankItem = Item(
            id = plank,
            displayName = "Wooden Plank",
            description = "",
            category = ItemCategory.RESOURCE,
            weightPerUnit = 800,
            maxStack = 99,
        )
        val items = StubItems(mapOf(plank to plankItem))
        val recipes = StubRecipes(listOf(openRecipe))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val view = tool.invoke(toolContext).recipes.single().output
        assertEquals(ItemCategory.RESOURCE, view.category)
        assertEquals(800, view.weightPerUnit)
        assertEquals(99, view.maxStack)
        assertNull(view.consumable)
        assertNull(view.harvestSkill)
        assertNull(view.equipmentStats)
    }

    @Test
    fun `consumable outputs project the refilled gauge and amount`() {
        val berryId = ItemId("BERRY")
        val berry = Item(
            id = berryId,
            displayName = "Wild Berries",
            description = "",
            category = ItemCategory.RESOURCE,
            weightPerUnit = 50,
            maxStack = 200,
            consumable = dev.gvart.genesara.world.ConsumableEffect(
                gauge = dev.gvart.genesara.world.Gauge.HUNGER,
                amount = 15,
            ),
            harvestSkill = SkillId("FORAGING"),
        )
        val berryRecipe = recipe("BERRY_PRESERVES", output = berryId, skill = carpentry, requiredLevel = 0, unlock = RecipeUnlockMode.Open)
        val items = StubItems(mapOf(berryId to berry))
        val recipes = StubRecipes(listOf(berryRecipe))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val view = tool.invoke(toolContext).recipes.single().output
        val effect = assertNotNull(view.consumable)
        assertEquals(Gauge.HUNGER, effect.gauge)
        assertEquals(15, effect.amount)
        assertEquals("FORAGING", view.harvestSkill)
        assertEquals(ItemCategory.RESOURCE, view.category)
        assertNull(view.equipmentStats)
    }

    @Test
    fun `equipmentStats projects bonuses, requirements, and weapon profile for equipment outputs`() {
        val swordId = ItemId("IRON_SWORD")
        val sword = Item(
            id = swordId,
            displayName = "Iron Sword",
            description = "",
            category = ItemCategory.EQUIPMENT,
            weightPerUnit = 0,
            maxStack = 1,
            validSlots = setOf(dev.gvart.genesara.world.EquipSlot.MAIN_HAND),
            twoHanded = false,
            maxDurability = 100,
            damageType = dev.gvart.genesara.world.DamageType.SLASH,
            weaponPower = 8,
            combatSkill = SkillId("SWORD"),
            range = 1,
            requiredAttributes = mapOf(dev.gvart.genesara.player.Attribute.STRENGTH to 12),
            requiredSkills = mapOf(SkillId("SMITHING") to 5),
            bonuses = listOf(
                dev.gvart.genesara.world.EquippedBonus.AttributeBonus(dev.gvart.genesara.player.Attribute.STRENGTH, 1),
                dev.gvart.genesara.world.EquippedBonus.PassiveBuff(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS, 2),
            ),
        )
        val swordRecipe = recipe("IRON_SWORD_BASIC", output = swordId, skill = carpentry, requiredLevel = 0, unlock = RecipeUnlockMode.Open)
        val items = StubItems(mapOf(swordId to sword, plank to itemFor(plank, "Wooden Plank")))
        val recipes = StubRecipes(listOf(swordRecipe))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val view = tool.invoke(toolContext).recipes.single()
        val stats = assertNotNull(view.output.equipmentStats)
        assertEquals(listOf(EquipSlot.MAIN_HAND), stats.slots)
        assertEquals(false, stats.twoHanded)
        assertEquals(100, stats.maxDurability)
        assertEquals(DamageType.SLASH, stats.damageType)
        assertEquals(8, stats.weaponPower)
        assertEquals(1, stats.range)
        assertEquals("SWORD", stats.combatSkill)
        assertEquals(mapOf(Attribute.STRENGTH to 12), stats.requiredAttributes)
        assertEquals(mapOf("SMITHING" to 5), stats.requiredSkills)
        assertEquals(
            setOf("STRENGTH" to 1, "SLASH_DAMAGE_BONUS" to 2),
            stats.bonuses.map { it.target to it.magnitude }.toSet(),
        )
    }

    @Test
    fun `equipmentStats projects rarity-scaled previews for weaponPower and maxDurability`() {
        val swordId = ItemId("IRON_SWORD")
        val sword = Item(
            id = swordId,
            displayName = "Iron Sword",
            description = "",
            category = ItemCategory.EQUIPMENT,
            weightPerUnit = 0,
            maxStack = 1,
            validSlots = setOf(dev.gvart.genesara.world.EquipSlot.MAIN_HAND),
            twoHanded = false,
            maxDurability = 100,
            damageType = dev.gvart.genesara.world.DamageType.SLASH,
            weaponPower = 8,
            combatSkill = SkillId("SWORD"),
            range = 1,
        )
        val swordRecipe = recipe("IRON_SWORD_BASIC", output = swordId, skill = carpentry, requiredLevel = 0, unlock = RecipeUnlockMode.Open)
        val items = StubItems(mapOf(swordId to sword))
        val recipes = StubRecipes(listOf(swordRecipe))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val stats = assertNotNull(tool.invoke(toolContext).recipes.single().output.equipmentStats)
        assertEquals(
            mapOf(Rarity.COMMON to 8, Rarity.UNCOMMON to 10, Rarity.RARE to 12, Rarity.EPIC to 14, Rarity.LEGENDARY to 16),
            stats.weaponPowerByRarity,
        )
        assertEquals(
            mapOf(Rarity.COMMON to 100, Rarity.UNCOMMON to 125, Rarity.RARE to 150, Rarity.EPIC to 175, Rarity.LEGENDARY to 200),
            stats.maxDurabilityByRarity,
        )
    }

    @Test
    fun `equipmentStats omits rarity preview maps when template fields are null`() {
        val tunicId = ItemId("LINEN_TUNIC")
        val tunic = Item(
            id = tunicId,
            displayName = "Linen Tunic",
            description = "",
            category = ItemCategory.EQUIPMENT,
            weightPerUnit = 0,
            maxStack = 1,
            validSlots = setOf(dev.gvart.genesara.world.EquipSlot.CHEST),
            twoHanded = false,
        )
        val tunicRecipe = recipe("LINEN_TUNIC_BASIC", output = tunicId, skill = carpentry, requiredLevel = 0, unlock = RecipeUnlockMode.Open)
        val items = StubItems(mapOf(tunicId to tunic))
        val recipes = StubRecipes(listOf(tunicRecipe))
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val stats = assertNotNull(tool.invoke(toolContext).recipes.single().output.equipmentStats)
        assertNull(stats.weaponPower)
        assertNull(stats.maxDurability)
        assertNull(stats.weaponPowerByRarity)
        assertNull(stats.maxDurabilityByRarity)
    }

    @Test
    fun `output and input views carry the displayName from the item catalog`() {
        val tool = GetRecipesTool(recipes, items, skillsAt(carpentry, level = 0), StubLedger(emptySet()), activity)

        val view = tool.invoke(toolContext).recipes.first { it.recipeId == openRecipe.id.value }
        assertEquals("Wooden Plank", view.output.name)
    }

    private fun recipe(
        id: String,
        output: ItemId,
        skill: SkillId,
        requiredLevel: Int,
        unlock: RecipeUnlockMode,
    ) = Recipe(
        id = RecipeId(id),
        output = RecipeOutput(output, quantity = 1),
        inputs = emptyMap(),
        requiredStation = BuildingCategoryHint.CRAFTING_STATION_WOOD,
        requiredSkill = skill,
        requiredSkillLevel = requiredLevel,
        staminaCost = 1,
        unlockMode = unlock,
    )

    private fun itemFor(id: ItemId, name: String) = Item(
        id = id,
        displayName = name,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 1,
        maxStack = 100,
    )

    private fun skillsAt(skill: SkillId, level: Int): AgentSkillsRegistry = object : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = mapOf(skill to AgentSkillState(skill = skill, xp = 0, level = level, slotIndex = 0, recommendCount = 0)),
            slotCount = 8,
            slotsFilled = 1,
        )
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int) = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class StubRecipes(private val all: List<Recipe>) : RecipeLookup {
        override fun byId(id: RecipeId): Recipe? = all.firstOrNull { it.id == id }
        override fun all(): List<Recipe> = all
    }

    private class StubItems(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubLedger(private val known: Set<RecipeId>) : AgentKnownRecipesGateway {
        override fun recordIfAbsent(
            agent: AgentId,
            recipe: RecipeId,
            source: RecipeLearnSource,
            sourceRef: String,
            tick: Long,
        ): Boolean = true

        override fun known(agent: AgentId): Set<RecipeId> = known
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
