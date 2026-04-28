package dev.gvart.genesara.account

interface AccountAuthenticator {
    /**
     * Returns the [Player] when credentials are valid, null otherwise.
     * Performs constant-time password comparison via the underlying [org.springframework.security.crypto.password.PasswordEncoder].
     */
    fun verify(username: String, password: String): Player?
}
