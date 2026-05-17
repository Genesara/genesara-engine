package dev.gvart.genesara.world.internal.mesh

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Maps a desired node count to the nearest Goldberg subdivision frequency T that can actually be
 * realized (faces = 10*T^2 + 2). Clamped to [2, 30] to mirror the editor.
 */
fun frequencyForNodeCount(nodeCount: Int): Int {
    val raw = sqrt(max(0, nodeCount - 2) / 10.0).roundToInt()
    return min(30, max(2, raw))
}

fun faceCountForFrequency(t: Int): Int = 10 * t * t + 2
