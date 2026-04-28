package dev.gvart.genesara.admin

interface AdminTokenStore {
    /** Issues a new long-lived bearer token for [adminId]. Returns the raw token string. */
    fun issue(adminId: AdminId): String

    /** Returns the [Admin] owning [token], or null if the token is unknown/revoked. */
    fun findByToken(token: String): Admin?

    /** Revokes a token. No-op if it doesn't exist. */
    fun revoke(token: String)
}
