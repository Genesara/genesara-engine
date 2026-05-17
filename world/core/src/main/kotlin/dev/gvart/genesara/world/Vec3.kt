package dev.gvart.genesara.world

data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun toList(): List<Double> = listOf(x, y, z)

    companion object {
        fun of(values: List<Double>): Vec3 {
            require(values.size == 3) { "Vec3 requires exactly 3 components, got ${values.size}" }
            return Vec3(values[0], values[1], values[2])
        }
    }
}
