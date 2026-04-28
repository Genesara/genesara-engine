package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.account.AccountAuthenticator
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

internal class AccountAuthenticationProvider(
    private val authenticator: AccountAuthenticator,
) : AuthenticationProvider {

    override fun authenticate(authentication: Authentication): Authentication {
        val username = authentication.name
        val password = authentication.credentials?.toString()
            ?: throw BadCredentialsException("missing password")
        val player = authenticator.verify(username, password)
            ?: throw BadCredentialsException("invalid credentials")
        return UsernamePasswordAuthenticationToken(
            player,
            null,
            listOf(SimpleGrantedAuthority("ROLE_PLAYER")),
        )
    }

    override fun supports(authentication: Class<*>): Boolean =
        UsernamePasswordAuthenticationToken::class.java.isAssignableFrom(authentication)
}
