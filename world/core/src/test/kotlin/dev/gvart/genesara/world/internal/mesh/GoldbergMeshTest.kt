package dev.gvart.genesara.world.internal.mesh

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldbergMeshTest {

    @Test
    fun `face count matches the 10T2+2 formula for representative T`() {
        for (t in listOf(2, 3, 4, 8)) {
            val mesh = generateGoldberg(t)
            assertEquals(faceCountForFrequency(t), mesh.faces.size, "T=$t")
        }
    }

    @Test
    fun `every mesh has exactly 12 pentagons regardless of T`() {
        for (t in listOf(2, 3, 4, 8)) {
            val pentagons = generateGoldberg(t).faces.count { it.isPentagon }
            assertEquals(12, pentagons, "T=$t")
        }
    }

    @Test
    fun `every face has 5 or 6 vertices`() {
        val mesh = generateGoldberg(4)
        for (face in mesh.faces) {
            assertTrue(face.vertices.size == 5 || face.vertices.size == 6, "face ${face.index} has ${face.vertices.size} vertices")
        }
    }

    @Test
    fun `neighbor relation is reciprocal — if A lists B, B lists A`() {
        val mesh = generateGoldberg(4)
        for (face in mesh.faces) {
            for (n in face.neighbors) {
                if (n < 0) continue
                val other = mesh.faces[n]
                assertTrue(face.index in other.neighbors, "${face.index} -> $n not reciprocal")
            }
        }
    }

    @Test
    fun `frequencyForNodeCount maps representative requested counts`() {
        // 10T^2 + 2 — pick known counts.
        assertEquals(2, frequencyForNodeCount(42))     // 10*4+2 = 42
        assertEquals(3, frequencyForNodeCount(92))     // 10*9+2 = 92
        assertEquals(4, frequencyForNodeCount(162))    // 10*16+2 = 162
        // out-of-range values are clamped to [2, 30]
        assertEquals(2, frequencyForNodeCount(0))
        assertEquals(2, frequencyForNodeCount(-99))
        assertEquals(30, frequencyForNodeCount(1_000_000))
    }
}
