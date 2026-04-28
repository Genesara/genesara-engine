package dev.gvart.agenticrpg.api.internal.rest.admin

import dev.gvart.agenticrpg.admin.Admin
import dev.gvart.agenticrpg.admin.AdminId
import dev.gvart.agenticrpg.admin.AdminTokenStore
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AdminLoginControllerTest {

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")

    @Test
    fun `login issues a fresh bearer token for the authenticated admin`() {
        val tokens = StubTokenStore(emit = "issued-token")
        val controller = AdminLoginController(tokens)

        val response = controller.login(admin)

        assertEquals("issued-token", response.token)
        assertEquals(listOf(admin.id), tokens.issued)
    }

    @Test
    fun `every login produces a fresh issue call`() {
        val tokens = StubTokenStore(emit = "t")
        val controller = AdminLoginController(tokens)

        repeat(3) { controller.login(admin) }

        assertEquals(3, tokens.issued.size)
    }

    private class StubTokenStore(private val emit: String) : AdminTokenStore {
        val issued = mutableListOf<AdminId>()
        override fun issue(adminId: AdminId): String {
            issued += adminId
            return emit
        }
        override fun findByToken(token: String): Admin? = null
        override fun revoke(token: String) = Unit
    }
}
