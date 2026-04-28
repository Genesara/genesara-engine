package dev.gvart.genesara.account

interface PlayerRegistrar {
    @Throws(UsernameAlreadyExists::class)
    fun register(username: String, password: String): Player
}

class UsernameAlreadyExists(val username: String) :
    RuntimeException("Username '$username' is already taken")
