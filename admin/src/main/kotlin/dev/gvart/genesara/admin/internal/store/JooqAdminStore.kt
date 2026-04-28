package dev.gvart.genesara.admin.internal.store

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuthenticator
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.admin.AdminLookup
import dev.gvart.genesara.admin.AdminRegistrar
import dev.gvart.genesara.admin.AdminTokenStore
import dev.gvart.genesara.admin.AdminUsernameAlreadyExists
import dev.gvart.genesara.admin.internal.AdminConfiguration.Companion.ADMIN_PASSWORD_ENCODER
import dev.gvart.genesara.admin.internal.jooq.tables.AdminCredentials.Companion.ADMIN_CREDENTIALS
import dev.gvart.genesara.admin.internal.jooq.tables.AdminTokens.Companion.ADMIN_TOKENS
import dev.gvart.genesara.admin.internal.jooq.tables.Admins.Companion.ADMINS
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID

@Component
internal class JooqAdminStore(
    private val dsl: DSLContext,
    @Qualifier(ADMIN_PASSWORD_ENCODER) private val passwordEncoder: PasswordEncoder,
) : AdminLookup, AdminRegistrar, AdminAuthenticator, AdminTokenStore {

    private val rng = SecureRandom()

    override fun find(id: AdminId): Admin? =
        dsl.select(ADMINS.ID, ADMINS.USERNAME)
            .from(ADMINS)
            .where(ADMINS.ID.eq(id.id))
            .fetchOne()
            ?.let { Admin(AdminId(it[ADMINS.ID]!!), it[ADMINS.USERNAME]!!) }

    override fun findByUsername(username: String): Admin? =
        dsl.select(ADMINS.ID, ADMINS.USERNAME)
            .from(ADMINS)
            .where(ADMINS.USERNAME_LOWER.eq(username.lowercase()))
            .fetchOne()
            ?.let { Admin(AdminId(it[ADMINS.ID]!!), it[ADMINS.USERNAME]!!) }

    override fun count(): Long =
        dsl.fetchCount(ADMINS).toLong()

    @Transactional
    override fun register(username: String, password: String): Admin {
        val id = UUID.randomUUID()
        val hash = passwordEncoder.encode(password) ?: error("PasswordEncoder returned null hash")
        try {
            dsl.insertInto(ADMINS)
                .set(ADMINS.ID, id)
                .set(ADMINS.USERNAME, username)
                .set(ADMINS.USERNAME_LOWER, username.lowercase())
                .execute()
        } catch (_: DuplicateKeyException) {
            throw AdminUsernameAlreadyExists(username)
        }
        dsl.insertInto(ADMIN_CREDENTIALS)
            .set(ADMIN_CREDENTIALS.ADMIN_ID, id)
            .set(ADMIN_CREDENTIALS.PASSWORD_HASH, hash)
            .execute()
        return Admin(AdminId(id), username)
    }

    override fun verify(username: String, password: String): Admin? {
        val row = dsl.select(ADMINS.ID, ADMINS.USERNAME, ADMIN_CREDENTIALS.PASSWORD_HASH)
            .from(ADMINS)
            .join(ADMIN_CREDENTIALS).on(ADMIN_CREDENTIALS.ADMIN_ID.eq(ADMINS.ID))
            .where(ADMINS.USERNAME_LOWER.eq(username.lowercase()))
            .fetchOne()
            ?: return null
        val hash = row[ADMIN_CREDENTIALS.PASSWORD_HASH]!!
        return if (passwordEncoder.matches(password, hash)) {
            Admin(AdminId(row[ADMINS.ID]!!), row[ADMINS.USERNAME]!!)
        } else null
    }

    override fun issue(adminId: AdminId): String {
        val token = randomToken()
        dsl.insertInto(ADMIN_TOKENS)
            .set(ADMIN_TOKENS.TOKEN, token)
            .set(ADMIN_TOKENS.ADMIN_ID, adminId.id)
            .execute()
        return token
    }

    override fun findByToken(token: String): Admin? =
        dsl.select(ADMINS.ID, ADMINS.USERNAME)
            .from(ADMIN_TOKENS)
            .join(ADMINS).on(ADMINS.ID.eq(ADMIN_TOKENS.ADMIN_ID))
            .where(ADMIN_TOKENS.TOKEN.eq(token))
            .fetchOne()
            ?.let { Admin(AdminId(it[ADMINS.ID]!!), it[ADMINS.USERNAME]!!) }

    override fun revoke(token: String) {
        dsl.deleteFrom(ADMIN_TOKENS).where(ADMIN_TOKENS.TOKEN.eq(token)).execute()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(48)
        rng.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }
}
