package dev.gvart.genesara.player

@JvmInline
value class PerkId(val value: String) {
    init {
        require(value.isNotBlank()) { "PerkId must not be blank" }
    }
}
