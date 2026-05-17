package dev.gvart.genesara.world.economy.internal.crafting

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLearnSource
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.RecipeUnlockMode
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeLearningImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val perk = PerkId("SMITHING_FORGE_MASTER")
    private val scroll = ItemId("RECIPE_SCROLL_BLADE")
    private val legendaryBlade = RecipeId("LEGENDARY_BLADE")
    private val greaterPotion = RecipeId("GREATER_POTION")

    @Test
    fun `learnFromPerk writes ledger and emits RecipeLearned for each newly-learned recipe`() {
        val gateway = RecordingGateway()
        val publisher = RecordingPublisher()
        val learning = RecipeLearningImpl(gateway, indexWith(legendaryBlade, RecipeUnlockMode.ClassPerk(perk)), publisher)

        val newlyLearned = learning.learnFromPerk(agent, perk, tick = 10L)

        assertEquals(listOf(legendaryBlade), newlyLearned)
        assertEquals(listOf(Triple(legendaryBlade, RecipeLearnSource.CLASS_PERK, perk.value)), gateway.writes)
        val event = publisher.events.filterIsInstance<AgentEvent.RecipeLearned>().single()
        assertEquals(legendaryBlade.value, event.recipeId)
        assertEquals(RecipeLearnSource.CLASS_PERK.name, event.source)
        assertEquals(perk.value, event.sourceRef)
        assertEquals(10L, event.tick)
    }

    @Test
    fun `learnFromItem emits RecipeLearned for items in the catalog reverse-index`() {
        val gateway = RecordingGateway()
        val publisher = RecordingPublisher()
        val learning = RecipeLearningImpl(gateway, indexWith(greaterPotion, RecipeUnlockMode.ItemLearned(scroll)), publisher)

        val newlyLearned = learning.learnFromItem(agent, scroll, tick = 5L)

        assertEquals(listOf(greaterPotion), newlyLearned)
        val event = publisher.events.filterIsInstance<AgentEvent.RecipeLearned>().single()
        assertEquals(RecipeLearnSource.ITEM_LEARNED.name, event.source)
        assertEquals(scroll.value, event.sourceRef)
    }

    @Test
    fun `duplicate learn does not re-emit RecipeLearned`() {
        val gateway = RecordingGateway().apply { suppressInsert(greaterPotion) }
        val publisher = RecordingPublisher()
        val learning = RecipeLearningImpl(gateway, indexWith(greaterPotion, RecipeUnlockMode.ItemLearned(scroll)), publisher)

        val newlyLearned = learning.learnFromItem(agent, scroll, tick = 1L)

        assertTrue(newlyLearned.isEmpty())
        assertTrue(publisher.events.none { it is AgentEvent.RecipeLearned })
    }

    @Test
    fun `learning a perk with no recipe-teaching entries is a no-op`() {
        val gateway = RecordingGateway()
        val publisher = RecordingPublisher()
        val learning = RecipeLearningImpl(gateway, RecipeUnlockIndex(StubRecipes(emptyList())), publisher)

        learning.learnFromPerk(agent, perk, tick = 1L)

        assertTrue(gateway.writes.isEmpty())
        assertTrue(publisher.events.isEmpty())
    }

    private fun indexWith(recipeId: RecipeId, unlock: RecipeUnlockMode): RecipeUnlockIndex {
        val r = Recipe(
            id = recipeId,
            output = RecipeOutput(ItemId("OUT"), quantity = 1),
            inputs = emptyMap(),
            requiredStation = BuildingCategoryHint.CRAFTING_STATION_WOOD,
            requiredSkill = SkillId("CARPENTRY"),
            requiredSkillLevel = 0,
            staminaCost = 1,
            unlockMode = unlock,
        )
        return RecipeUnlockIndex(StubRecipes(listOf(r)))
    }

    private class StubRecipes(private val all: List<Recipe>) : RecipeLookup {
        override fun byId(id: RecipeId): Recipe? = all.firstOrNull { it.id == id }
        override fun all(): List<Recipe> = all
    }

    private class RecordingGateway : AgentKnownRecipesGateway {
        val writes = mutableListOf<Triple<RecipeId, RecipeLearnSource, String>>()
        private val suppressed = mutableSetOf<RecipeId>()

        fun suppressInsert(recipe: RecipeId) { suppressed += recipe }

        override fun recordIfAbsent(
            agent: AgentId,
            recipe: RecipeId,
            source: RecipeLearnSource,
            sourceRef: String,
            tick: Long,
        ): Boolean {
            if (recipe in suppressed) return false
            writes += Triple(recipe, source, sourceRef)
            return true
        }

        override fun known(agent: AgentId): Set<RecipeId> = writes.map { it.first }.toSet()
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) { events += event }
    }
}
