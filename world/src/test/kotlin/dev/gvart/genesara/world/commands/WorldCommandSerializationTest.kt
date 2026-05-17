package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The wire-format discriminator strings are a contract: a Kotlin rename
 * must not change them, otherwise in-flight Redis-queued commands silently
 * become un-deserializable when a pod with the new code drains a queue
 * written by an old pod (or vice versa).
 *
 * Each test asserts (a) the discriminator emitted on serialize and (b)
 * round-trip equivalence — submit-side and drain-side are different pods
 * in production, so JSON has to carry every field needed to reconstruct.
 */
class WorldCommandSerializationTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `every WorldCommand subtype carries a stable discriminator and survives round-trip`() {
        val agent = AgentId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val target = AgentId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        val cid = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val chest = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val drop = UUID.fromString("55555555-5555-5555-5555-555555555555")

        val expectations: List<Pair<String, WorldCommand>> = listOf(
            "spawn" to CoreCommand.SpawnAgent(agent, cid),
            "move" to CoreCommand.MoveAgent(agent, NodeId(42L), cid),
            "unspawn" to CoreCommand.UnspawnAgent(agent, cid),
            "harvest" to EconomyCommand.Harvest(agent, ItemId("WOOD"), cid),
            "consume" to BodyCommand.ConsumeItem(agent, ItemId("BERRY"), cid),
            "drink" to BodyCommand.Drink(agent, cid),
            "setSafeNode" to CoreCommand.SetSafeNode(agent, cid),
            "respawn" to BodyCommand.Respawn(agent, cid),
            "build" to EnvironmentCommand.BuildStructure(agent, BuildingType.STORAGE_CHEST, commandId = cid),
            "depositToChest" to EnvironmentCommand.DepositToChest(agent, chest, ItemId("WOOD"), 5, cid),
            "withdrawFromChest" to EnvironmentCommand.WithdrawFromChest(agent, chest, ItemId("STONE"), 3, cid),
            "craft" to EconomyCommand.CraftItem(agent, RecipeId("PLANK"), cid),
            "pickup" to BodyCommand.Pickup(agent, drop, cid),
            "attack" to CombatCommand.AttackTarget(agent, target, cid),
            "useAbility" to CombatCommand.UseAbility(agent, AbilityId("SWORD_POWER_STRIKE"), target, cid),
            "say" to CoreCommand.Say(agent, "hi", SpeechMode.NORMAL, SayChannel.LOCAL, cid),
            "tradeOffer" to EconomyCommand.TradeOffer(
                agent = agent,
                recipient = target,
                offered = mapOf(ItemId("WOOD") to 2),
                requested = mapOf(ItemId("STONE") to 1),
                tradeId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
                commandId = cid,
            ),
            "tradeRespond" to EconomyCommand.TradeRespond(
                agent = agent,
                tradeId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
                accept = true,
                commandId = cid,
            ),
            "toggleGate" to EnvironmentCommand.ToggleGate(
                agent = agent,
                gateId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
                commandId = cid,
            ),
            "extract" to EconomyCommand.Extract(agent, ItemId("GOLD"), cid),
            // Source-bearing craft variant — the `source` field is optional but
            // must round-trip for recipes that declare `requires-source` (e.g. GATE_KEY_COPY).
            "craft" to EconomyCommand.CraftItem(
                agent = agent,
                recipe = RecipeId("GATE_KEY_COPY"),
                source = UUID.fromString("99999999-9999-9999-9999-999999999999"),
                commandId = cid,
            ),
        )

        for ((discriminator, command) in expectations) {
            val json = mapper.writeValueAsString(command)
            assertTrue(
                "\"@type\":\"$discriminator\"" in json,
                "expected discriminator '$discriminator' in JSON for ${command::class.simpleName}: $json",
            )
            val roundTripped = mapper.readValue(json, WorldCommand::class.java)
            assertEquals(command, roundTripped, "round-trip mismatch for ${command::class.simpleName}")
        }
    }

    @Test
    fun `fixed JSON payload deserializes — wire contract for cross-pod queues`() {
        val json = """
            {
              "@type": "move",
              "agent": "11111111-1111-1111-1111-111111111111",
              "to": 42,
              "commandId": "33333333-3333-3333-3333-333333333333"
            }
        """.trimIndent()

        val command = mapper.readValue(json, WorldCommand::class.java)

        val move = assertIs<CoreCommand.MoveAgent>(command)
        assertEquals(AgentId(UUID.fromString("11111111-1111-1111-1111-111111111111")), move.agent)
        assertEquals(NodeId(42L), move.to)
        assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), move.commandId)
    }
}
