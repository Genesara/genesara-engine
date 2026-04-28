package dev.gvart.agenticrpg.api.internal.security

import dev.gvart.agenticrpg.admin.AdminAuthenticator
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

internal class AdminAuthenticationProvider(
    private val authenticator: AdminAuthenticator,
) : AuthenticationProvider {

    override fun authenticate(authentication: Authentication): Authentication {
        val username = authentication.name
        val password = authentication.credentials?.toString()
            ?: throw BadCredentialsException("missing password")
        val admin = authenticator.verify(username, password)
            ?: throw BadCredentialsException("invalid credentials")
        return UsernamePasswordAuthenticationToken(
            admin,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
    }

    override fun supports(authentication: Class<*>): Boolean =
        UsernamePasswordAuthenticationToken::class.java.isAssignableFrom(authentication)
}
