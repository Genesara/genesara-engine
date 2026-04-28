package dev.gvart.agenticrpg.world.internal.mesh

import org.springframework.stereotype.Component

@Component
internal class GoldbergMeshGenerator {

    /**
     * Generates the icosphere/Goldberg mesh whose face count is closest to [requestedNodeCount],
     * subject to the discrete frequencies T in [2, 30].
     */
    fun generateForRequestedNodeCount(requestedNodeCount: Int): GoldbergMesh =
        generateGoldberg(frequencyForNodeCount(requestedNodeCount))

    /** Generates the icosphere/Goldberg mesh for an exact frequency [t]. */
    fun generateForFrequency(t: Int): GoldbergMesh = generateGoldberg(t)
}
