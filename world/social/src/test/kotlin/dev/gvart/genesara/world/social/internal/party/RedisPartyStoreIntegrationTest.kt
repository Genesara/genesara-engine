package dev.gvart.genesara.world.social.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyMember
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Testcontainers
class RedisPartyStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisPartyStore

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        store = RedisPartyStore(template)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `create then find round-trips party with leader and members`() {
        val leader = AgentId(UUID.randomUUID())
        val member = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        val party = Party(
            partyId = partyId,
            leaderId = leader,
            members = listOf(
                PartyMember(leader, joinedAtTick = 100L),
                PartyMember(member, joinedAtTick = 105L),
            ),
            formedAtTick = 100L,
        )

        store.create(party)

        assertEquals(party, store.find(partyId))
    }

    @Test
    fun `findByAgent resolves both leader and members via the agent-party index`() {
        val leader = AgentId(UUID.randomUUID())
        val member = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        val party = Party(
            partyId = partyId,
            leaderId = leader,
            members = listOf(
                PartyMember(leader, joinedAtTick = 100L),
                PartyMember(member, joinedAtTick = 105L),
            ),
            formedAtTick = 100L,
        )
        store.create(party)

        assertEquals(party, store.findByAgent(leader))
        assertEquals(party, store.findByAgent(member))
    }

    @Test
    fun `findByAgent returns null for an agent not in any party`() {
        assertNull(store.findByAgent(AgentId(UUID.randomUUID())))
    }

    @Test
    fun `addMember appends and indexes the new agent`() {
        val leader = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        store.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, joinedAtTick = 100L)),
                formedAtTick = 100L,
            )
        )

        val joiner = AgentId(UUID.randomUUID())
        val updated = store.addMember(partyId, PartyMember(joiner, joinedAtTick = 110L))

        assertNotNull(updated)
        assertEquals(2, updated.size)
        assertEquals(partyId, store.findByAgent(joiner)?.partyId)
    }

    @Test
    fun `addMember returns null when party does not exist`() {
        val ghost = PartyId(UUID.randomUUID())
        assertNull(store.addMember(ghost, PartyMember(AgentId(UUID.randomUUID()), 0L)))
    }

    @Test
    fun `removeMember drops the row and clears the agent-party index`() {
        val leader = AgentId(UUID.randomUUID())
        val member = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        store.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(
                    PartyMember(leader, joinedAtTick = 100L),
                    PartyMember(member, joinedAtTick = 105L),
                ),
                formedAtTick = 100L,
            )
        )

        val after = store.removeMember(partyId, member)

        assertNotNull(after)
        assertEquals(1, after.size)
        assertNull(store.findByAgent(member))
    }

    @Test
    fun `removeMember returns null when agent was not a member`() {
        val leader = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        store.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L)),
                formedAtTick = 0L,
            )
        )

        assertNull(store.removeMember(partyId, AgentId(UUID.randomUUID())))
    }

    @Test
    fun `replaceLeader updates the leader pointer without touching membership`() {
        val original = AgentId(UUID.randomUUID())
        val successor = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        store.create(
            Party(
                partyId = partyId,
                leaderId = original,
                members = listOf(
                    PartyMember(original, joinedAtTick = 100L),
                    PartyMember(successor, joinedAtTick = 105L),
                ),
                formedAtTick = 100L,
            )
        )

        val updated = store.replaceLeader(partyId, successor)

        assertNotNull(updated)
        assertEquals(successor, updated.leaderId)
        assertEquals(2, updated.size)
    }

    @Test
    fun `delete wipes party rows and clears every agent-party index`() {
        val leader = AgentId(UUID.randomUUID())
        val member = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        store.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(
                    PartyMember(leader, joinedAtTick = 100L),
                    PartyMember(member, joinedAtTick = 105L),
                ),
                formedAtTick = 100L,
            )
        )

        store.delete(partyId)

        assertNull(store.find(partyId))
        assertNull(store.findByAgent(leader))
        assertNull(store.findByAgent(member))
    }

    @Test
    fun `members are ordered by joinedAtTick ascending`() {
        val a = AgentId(UUID.randomUUID())
        val b = AgentId(UUID.randomUUID())
        val c = AgentId(UUID.randomUUID())
        val partyId = PartyId(UUID.randomUUID())
        store.create(
            Party(
                partyId = partyId,
                leaderId = a,
                members = listOf(
                    PartyMember(a, joinedAtTick = 100L),
                    PartyMember(c, joinedAtTick = 130L),
                    PartyMember(b, joinedAtTick = 120L),
                ),
                formedAtTick = 100L,
            )
        )

        val loaded = store.find(partyId)!!
        assertEquals(listOf(a, b, c), loaded.members.map { it.agentId })
    }
}
