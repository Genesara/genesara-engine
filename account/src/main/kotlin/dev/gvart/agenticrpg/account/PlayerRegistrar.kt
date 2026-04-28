package dev.gvart.agenticrpg.account

interface PlayerRegistrar {
    @Throws(UsernameAlreadyExists::class)
    fun register(username: String, password: String): Player
}

class UsernameAlreadyExists(val username: String) :
    RuntimeException("Username '$username' is already taken")
