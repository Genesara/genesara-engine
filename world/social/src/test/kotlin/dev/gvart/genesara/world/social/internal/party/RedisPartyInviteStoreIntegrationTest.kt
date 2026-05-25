package dev.gvart.genesara.world.social.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.PartyInvite
import dev.gvart.genesara.world.PartyInviteId
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
import kotlin.test.assertTrue

@Testcontainers
class RedisPartyInviteStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisPartyInviteStore

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        store = RedisPartyInviteStore(template)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `create then find round-trips invite fields`() {
        val invite = newInvite()

        store.create(invite, ttlSeconds = 120L)

        assertEquals(invite, store.find(invite.inviteId))
    }

    @Test
    fun `findByInvitee returns all live invites for an invitee`() {
        val invitee = AgentId(UUID.randomUUID())
        val a = newInvite(invitee = invitee)
        val b = newInvite(invitee = invitee)
        store.create(a, ttlSeconds = 120L)
        store.create(b, ttlSeconds = 120L)

        val loaded = store.findByInvitee(invitee)

        assertEquals(setOf(a, b), loaded.toSet())
    }

    @Test
    fun `findByInviter returns all live invites the agent has sent`() {
        val inviter = AgentId(UUID.randomUUID())
        val a = newInvite(inviter = inviter)
        val b = newInvite(inviter = inviter)
        store.create(a, ttlSeconds = 120L)
        store.create(b, ttlSeconds = 120L)

        assertEquals(setOf(a, b), store.findByInviter(inviter).toSet())
    }

    @Test
    fun `delete removes the invite and prunes both indexes`() {
        val invite = newInvite()
        store.create(invite, ttlSeconds = 120L)

        store.delete(invite.inviteId)

        assertNull(store.find(invite.inviteId))
        assertTrue(store.findByInvitee(invite.inviteeId).isEmpty())
        assertTrue(store.findByInviter(invite.inviterId).isEmpty())
    }

    @Test
    fun `deleteAllByInviter wipes every invite sent by the leader`() {
        val inviter = AgentId(UUID.randomUUID())
        val a = newInvite(inviter = inviter)
        val b = newInvite(inviter = inviter)
        val unrelated = newInvite()
        store.create(a, ttlSeconds = 120L)
        store.create(b, ttlSeconds = 120L)
        store.create(unrelated, ttlSeconds = 120L)

        val swept = store.deleteAllByInviter(inviter)

        assertEquals(setOf(a, b), swept.toSet())
        assertNull(store.find(a.inviteId))
        assertNull(store.find(b.inviteId))
        assertNotNull(store.find(unrelated.inviteId))
        assertTrue(store.findByInvitee(a.inviteeId).none { it.inviteId == a.inviteId })
        assertTrue(store.findByInvitee(b.inviteeId).none { it.inviteId == b.inviteId })
    }

    @Test
    fun `invite key respects the configured TTL`() {
        val invite = newInvite()
        store.create(invite, ttlSeconds = 1L)
        Thread.sleep(1_500)

        assertNull(store.find(invite.inviteId))
    }

    @Test
    fun `findByInvitee lazily prunes index entries whose invite key expired`() {
        val invite = newInvite()
        store.create(invite, ttlSeconds = 1L)
        Thread.sleep(1_500)

        val invitesAfterExpiry = store.findByInvitee(invite.inviteeId)

        assertTrue(invitesAfterExpiry.isEmpty())
        assertEquals(0L, template.opsForSet().size("agent:${invite.inviteeId.id}:invites"))
    }

    private fun newInvite(
        inviter: AgentId = AgentId(UUID.randomUUID()),
        invitee: AgentId = AgentId(UUID.randomUUID()),
    ): PartyInvite = PartyInvite(
        inviteId = PartyInviteId(UUID.randomUUID()),
        inviterId = inviter,
        inviteeId = invitee,
        sentAtTick = 100L,
        expiresAtTick = 220L,
    )
}
