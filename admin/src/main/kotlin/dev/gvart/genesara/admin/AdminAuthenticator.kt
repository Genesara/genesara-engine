package dev.gvart.genesara.admin

interface AdminAuthenticator {
    /** Constant-time password comparison via the configured [org.springframework.security.crypto.password.PasswordEncoder]. */
    fun verify(username: String, password: String): Admin?
}
