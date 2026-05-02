package dev.gvart.genesara.account.internal.store

import dev.gvart.genesara.account.AccountAuthenticator
import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerApiTokenStore
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.account.PlayerLookup
import dev.gvart.genesara.account.PlayerRegistrar
import dev.gvart.genesara.account.UsernameAlreadyExists
import dev.gvart.genesara.account.internal.jooq.tables.PlayerCredentials.Companion.PLAYER_CREDENTIALS
import dev.gvart.genesara.account.internal.jooq.tables.Players.Companion.PLAYERS
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
) : PlayerLookup, PlayerRegistrar, PlayerApiTokenStore, AccountAuthenticator {

    override fun find(id: PlayerId): Player? =
        dsl.select(PLAYERS.ID, PLAYERS.USERNAME, PLAYERS.API_TOKEN)
            .from(PLAYERS)
            .where(PLAYERS.ID.eq(id.id))
            .fetchOne()
            ?.let { Player(PlayerId(it[PLAYERS.ID]!!), it[PLAYERS.USERNAME]!!, it[PLAYERS.API_TOKEN]!!) }

    override fun findByUsername(username: String): Player? =
        dsl.select(PLAYERS.ID, PLAYERS.USERNAME, PLAYERS.API_TOKEN)
            .from(PLAYERS)
            .where(PLAYERS.USERNAME_LOWER.eq(username.lowercase()))
            .fetchOne()
            ?.let { Player(PlayerId(it[PLAYERS.ID]!!), it[PLAYERS.USERNAME]!!, it[PLAYERS.API_TOKEN]!!) }

    override fun findByApiToken(token: String): Player? =
        dsl.select(PLAYERS.ID, PLAYERS.USERNAME, PLAYERS.API_TOKEN)
            .from(PLAYERS)
            .where(PLAYERS.API_TOKEN.eq(token))
            .fetchOne()
            ?.let { Player(PlayerId(it[PLAYERS.ID]!!), it[PLAYERS.USERNAME]!!, it[PLAYERS.API_TOKEN]!!) }

    @Transactional
    override fun register(username: String, password: String): Player {
        val id = UUID.randomUUID()
        val hash = passwordEncoder.encode(password) ?: error("PasswordEncoder returned null hash")
        val token = mintToken()
        try {
            dsl.insertInto(PLAYERS)
                .set(PLAYERS.ID, id)
                .set(PLAYERS.USERNAME, username)
                .set(PLAYERS.USERNAME_LOWER, username.lowercase())
                .set(PLAYERS.API_TOKEN, token)
                .execute()
        } catch (_: DuplicateKeyException) {
            throw UsernameAlreadyExists(username)
        }
        dsl.insertInto(PLAYER_CREDENTIALS)
            .set(PLAYER_CREDENTIALS.PLAYER_ID, id)
            .set(PLAYER_CREDENTIALS.PASSWORD_HASH, hash)
            .execute()
        return Player(PlayerId(id), username, token)
    }

    @Transactional
    override fun rotate(playerId: PlayerId): String {
        val token = mintToken()
        val updated = dsl.update(PLAYERS)
            .set(PLAYERS.API_TOKEN, token)
            .where(PLAYERS.ID.eq(playerId.id))
            .execute()
        require(updated == 1) { "Cannot rotate api token: player ${playerId.id} not found" }
        return token
    }

    @Transactional(readOnly = true)
    override fun verify(username: String, password: String): Player? {
        val row = dsl.select(PLAYERS.ID, PLAYERS.USERNAME, PLAYERS.API_TOKEN, PLAYER_CREDENTIALS.PASSWORD_HASH)
            .from(PLAYERS)
            .join(PLAYER_CREDENTIALS).on(PLAYER_CREDENTIALS.PLAYER_ID.eq(PLAYERS.ID))
            .where(PLAYERS.USERNAME_LOWER.eq(username.lowercase()))
            .fetchOne()
            ?: return null
        val hash = row[PLAYER_CREDENTIALS.PASSWORD_HASH]!!
        return if (passwordEncoder.matches(password, hash)) {
            Player(PlayerId(row[PLAYERS.ID]!!), row[PLAYERS.USERNAME]!!, row[PLAYERS.API_TOKEN]!!)
        } else null
    }

    private fun mintToken(): String = "plr_" + UUID.randomUUID().toString().replace("-", "")
}
