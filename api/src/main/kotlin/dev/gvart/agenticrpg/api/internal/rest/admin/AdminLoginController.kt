package dev.gvart.agenticrpg.api.internal.rest.admin

import dev.gvart.agenticrpg.admin.Admin
import dev.gvart.agenticrpg.admin.AdminTokenStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/login")
internal class AdminLoginController(
    private val tokens: AdminTokenStore,
) {

    data class LoginResponse(val token: String)

    @PostMapping
    fun login(@AuthenticationPrincipal admin: Admin): LoginResponse =
        LoginResponse(token = tokens.issue(admin.id))
}
