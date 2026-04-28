package dev.gvart.agenticrpg.account.internal.store

import dev.gvart.agenticrpg.account.AccountAuthenticator
import dev.gvart.agenticrpg.account.Player
import dev.gvart.agenticrpg.account.PlayerId
import dev.gvart.agenticrpg.account.PlayerLookup
import dev.gvart.agenticrpg.account.PlayerRegistrar
import dev.gvart.agenticrpg.account.UsernameAlreadyExists
import dev.gvart.agenticrpg.account.internal.jooq.tables.Players.Companion.PLAYERS
import dev.gvart.agenticrpg.account.internal.jooq.tables.PlayerCredentials.Companion.PLAYER_CREDENTIALS
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqPlayerStore(
    private val dsl: DSLContext,
    private val passwordEncoder: PasswordEncoder,
) : PlayerLookup, PlayerRegistrar, AccountAuthenticator {

    override fun find(id: PlayerId): Player? =
        dsl.select(PLAYERS.ID, PLAYERS.USERNAME)
            .from(PLAYERS)
            .where(PLAYERS.ID.eq(id.id))
            .fetchOne()
            ?.let { Player(PlayerId(it[PLAYERS.ID]!!), it[PLAYERS.USERNAME]!!) }

    override fun findByUsername(username: String): Player? =
        dsl.select(PLAYERS.ID, PLAYERS.USERNAME)
            .from(PLAYERS)
            .where(PLAYERS.USERNAME_LOWER.eq(username.lowercase()))
            .fetchOne()
            ?.let { Player(PlayerId(it[PLAYERS.ID]!!), it[PLAYERS.USERNAME]!!) }

    @Transactional
    override fun register(username: String, password: String): Player {
        val id = UUID.randomUUID()
        val hash = passwordEncoder.encode(password) ?: error("PasswordEncoder returned null hash")
        try {
            dsl.insertInto(PLAYERS)
                .set(PLAYERS.ID, id)
                .set(PLAYERS.USERNAME, username)
                .set(PLAYERS.USERNAME_LOWER, username.lowercase())
                .execute()
        } catch (_: DuplicateKeyException) {
            throw UsernameAlreadyExists(username)
        }
        dsl.insertInto(PLAYER_CREDENTIALS)
            .set(PLAYER_CREDENTIALS.PLAYER_ID, id)
            .set(PLAYER_CREDENTIALS.PASSWORD_HASH, hash)
            .execute()
        return Player(PlayerId(id), username)
    }

    override fun verify(username: String, password: String): Player? {
        val row = dsl.select(PLAYERS.ID, PLAYERS.USERNAME, PLAYER_CREDENTIALS.PASSWORD_HASH)
            .from(PLAYERS)
            .join(PLAYER_CREDENTIALS).on(PLAYER_CREDENTIALS.PLAYER_ID.eq(PLAYERS.ID))
            .where(PLAYERS.USERNAME_LOWER.eq(username.lowercase()))
            .fetchOne()
            ?: return null
        val hash = row[PLAYER_CREDENTIALS.PASSWORD_HASH]!!
        return if (passwordEncoder.matches(password, hash)) {
            Player(PlayerId(row[PLAYERS.ID]!!), row[PLAYERS.USERNAME]!!)
        } else null
    }
}
