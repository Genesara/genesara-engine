package dev.gvart.agenticrpg.admin

interface AdminRegistrar {
    @Throws(AdminUsernameAlreadyExists::class)
    fun register(username: String, password: String): Admin
}

class AdminUsernameAlreadyExists(val username: String) :
    RuntimeException("Admin username '$username' is already taken")
