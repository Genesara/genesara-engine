package dev.gvart.genesara.world

@JvmInline
value class CropId(val value: String) {
    init {
        require(value.isNotBlank()) { "CropId must be non-blank" }
    }
}
