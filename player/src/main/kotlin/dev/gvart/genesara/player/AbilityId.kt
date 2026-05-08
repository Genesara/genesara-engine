package dev.gvart.genesara.player

@JvmInline
value class AbilityId(val value: String) {
    init {
        require(value.isNotBlank()) { "AbilityId must not be blank" }
    }
}
