package dev.gvart.genesara.player

@JvmInline
value class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank" }
    }
}
