package dev.gvart.genesara.world.internal.mesh

import dev.gvart.genesara.world.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure Kotlin port of `agentic-rpg-map-editor/src/utils/goldberg.ts`. Produces the same Goldberg
 * polyhedron face data (12 pentagons + 10*T^2 - 10 hexagons) for a given subdivision frequency T.
 *
 * Output is deterministic and bit-identical to the TypeScript version up to floating-point rounding;
 * the consumer should not rely on any specific ordering beyond what's documented per face.
 */
data class GoldbergFace(
    val index: Int,
    val centroid: Vec3,
    val vertices: List<Vec3>,
    val neighbors: List<Int>,
    val isPentagon: Boolean,
)

data class GoldbergMesh(
    val faces: List<GoldbergFace>,
    val frequency: Int,
)

private val PHI = (1.0 + sqrt(5.0)) / 2.0

private fun normalize(v: DoubleArray): DoubleArray {
    val m = hypot(hypot(v[0], v[1]), v[2]).let { if (it == 0.0) 1.0 else it }
    return doubleArrayOf(v[0] / m, v[1] / m, v[2] / m)
}

private fun sub(a: DoubleArray, b: DoubleArray): DoubleArray =
    doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

private fun scale(a: DoubleArray, s: Double): DoubleArray =
    doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)

private fun dot(a: DoubleArray, b: DoubleArray): Double =
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
    doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

private fun vertexKey(v: DoubleArray): String {
    val p = 1_000_000.0
    return "${(v[0] * p).toLong()}|${(v[1] * p).toLong()}|${(v[2] * p).toLong()}"
}

private data class Icosahedron(
    val vertices: List<DoubleArray>,
    val faces: List<IntArray>,
)

private fun baseIcosahedron(): Icosahedron {
    val t = PHI
    val raw = listOf(
        doubleArrayOf(-1.0, t, 0.0),
        doubleArrayOf(1.0, t, 0.0),
        doubleArrayOf(-1.0, -t, 0.0),
        doubleArrayOf(1.0, -t, 0.0),
        doubleArrayOf(0.0, -1.0, t),
        doubleArrayOf(0.0, 1.0, t),
        doubleArrayOf(0.0, -1.0, -t),
        doubleArrayOf(0.0, 1.0, -t),
        doubleArrayOf(t, 0.0, -1.0),
        doubleArrayOf(t, 0.0, 1.0),
        doubleArrayOf(-t, 0.0, -1.0),
        doubleArrayOf(-t, 0.0, 1.0),
    ).map(::normalize)
    val faces = listOf(
        intArrayOf(0, 11, 5),
        intArrayOf(0, 5, 1),
        intArrayOf(0, 1, 7),
        intArrayOf(0, 7, 10),
        intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9),
        intArrayOf(5, 11, 4),
        intArrayOf(11, 10, 2),
        intArrayOf(10, 7, 6),
        intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4),
        intArrayOf(3, 4, 2),
        intArrayOf(3, 2, 6),
        intArrayOf(3, 6, 8),
        intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5),
        intArrayOf(2, 4, 11),
        intArrayOf(6, 2, 10),
        intArrayOf(8, 6, 7),
        intArrayOf(9, 8, 1),
    )
    return Icosahedron(raw, faces)
}

private data class GeodesicMesh(
    val vertices: List<DoubleArray>,
    val triangles: List<IntArray>,
    val vertexTriangles: List<MutableList<Int>>,
)

private fun edgeKey(a: Int, b: Int): String =
    if (a < b) "$a|$b" else "$b|$a"

private fun buildGeodesic(t: Int): GeodesicMesh {
    val base = baseIcosahedron()
    val vertices = mutableListOf<DoubleArray>()
    val vertexIndex = HashMap<String, Int>()

    fun addVertex(v: DoubleArray): Int {
        val n = normalize(v)
        val key = vertexKey(n)
        vertexIndex[key]?.let { return it }
        val idx = vertices.size
        vertices += n
        vertexIndex[key] = idx
        return idx
    }

    val triangles = mutableListOf<IntArray>()

    for (face in base.faces) {
        val a = base.vertices[face[0]]
        val b = base.vertices[face[1]]
        val c = base.vertices[face[2]]
        val grid = Array(t + 1) { IntArray(0) }
        for (i in 0..t) {
            grid[i] = IntArray(t - i + 1)
            for (j in 0..(t - i)) {
                val k = t - i - j
                val p = doubleArrayOf(
                    (i * a[0] + j * b[0] + k * c[0]) / t,
                    (i * a[1] + j * b[1] + k * c[1]) / t,
                    (i * a[2] + j * b[2] + k * c[2]) / t,
                )
                grid[i][j] = addVertex(p)
            }
        }
        for (i in 0 until t) {
            for (j in 0 until (t - i)) {
                val v00 = grid[i][j]
                val v10 = grid[i + 1][j]
                val v01 = grid[i][j + 1]
                triangles += intArrayOf(v00, v10, v01)
                if (j < t - i - 1) {
                    val v11 = grid[i + 1][j + 1]
                    triangles += intArrayOf(v10, v11, v01)
                }
            }
        }
    }

    val vertexTriangles = MutableList(vertices.size) { mutableListOf<Int>() }
    val edgeMap = HashMap<String, IntArray>()
    triangles.forEachIndexed { tIdx, tri ->
        val (a, b, c) = Triple(tri[0], tri[1], tri[2])
        vertexTriangles[a] += tIdx
        vertexTriangles[b] += tIdx
        vertexTriangles[c] += tIdx
        for ((x, y) in listOf(a to b, b to c, c to a)) {
            val key = edgeKey(x, y)
            val cur = edgeMap[key]
            if (cur != null) cur[1] = tIdx
            else edgeMap[key] = intArrayOf(tIdx, -1)
        }
    }

    return GeodesicMesh(vertices, triangles, vertexTriangles)
}

private fun triangleCentroid(tri: IntArray, verts: List<DoubleArray>): DoubleArray {
    val a = verts[tri[0]]
    val b = verts[tri[1]]
    val c = verts[tri[2]]
    return normalize(
        doubleArrayOf(
            (a[0] + b[0] + c[0]) / 3,
            (a[1] + b[1] + c[1]) / 3,
            (a[2] + b[2] + c[2]) / 3,
        ),
    )
}

private fun pickOrthogonal(n: DoubleArray): DoubleArray {
    val ax = abs(n[0])
    val ay = abs(n[1])
    val az = abs(n[2])
    val ref: DoubleArray = when {
        ax < ay && ax < az -> doubleArrayOf(1.0, 0.0, 0.0)
        ay < az -> doubleArrayOf(0.0, 1.0, 0.0)
        else -> doubleArrayOf(0.0, 0.0, 1.0)
    }
    return normalize(sub(ref, scale(n, dot(ref, n))))
}

private fun toVec3(a: DoubleArray): Vec3 = Vec3(a[0], a[1], a[2])

fun generateGoldberg(t: Int): GoldbergMesh {
    require(t >= 1) { "generateGoldberg requires integer T >= 1, got $t" }
    val geo = buildGeodesic(t)
    val triCentroids: List<DoubleArray> = geo.triangles.map { tri -> triangleCentroid(tri, geo.vertices) }

    val faces = geo.vertices.mapIndexed { vi, v ->
        val tris = geo.vertexTriangles[vi]
        val corners: List<DoubleArray> = tris.map { tIdx -> triCentroids[tIdx] }

        val u = pickOrthogonal(v)
        val w = normalize(cross(v, u))

        // Sort corners CCW around v in the tangent plane (u, w).
        data class Indexed(val i: Int, val angle: Double)
        val indexed = corners.mapIndexed { i, c ->
            val d = sub(c, v)
            Indexed(i, atan2(dot(d, w), dot(d, u)))
        }.sortedBy { it.angle }

        val sortedCorners = indexed.map { corners[it.i] }.toMutableList()
        val sortedTris = indexed.map { tris[it.i] }

        // Compute neighbors: neighbor k is opposite the edge between corner k and corner k+1.
        val neighbors = MutableList(sortedTris.size) { 0 }
        for (k in sortedTris.indices) {
            val tA = geo.triangles[sortedTris[k]]
            val tB = geo.triangles[sortedTris[(k + 1) % sortedTris.size]]
            val sharedInA = tA.filter { x -> x != vi && x in tB }
            neighbors[k] = if (sharedInA.isNotEmpty()) sharedInA[0] else -1
        }

        val cSum = doubleArrayOf(0.0, 0.0, 0.0)
        for (c in sortedCorners) {
            cSum[0] += c[0]; cSum[1] += c[1]; cSum[2] += c[2]
        }
        val centroid = normalize(
            doubleArrayOf(
                cSum[0] / sortedCorners.size,
                cSum[1] / sortedCorners.size,
                cSum[2] / sortedCorners.size,
            ),
        )

        // Enforce outward winding (cross of first two edges points outward).
        val e1 = sub(sortedCorners[1], sortedCorners[0])
        val e2 = sub(sortedCorners[2], sortedCorners[0])
        val nrm = cross(e1, e2)
        if (dot(nrm, centroid) < 0) {
            sortedCorners.reverse()
            neighbors.reverse()
            // After reversing, neighbor[k] should match edge (k, k+1). Shift by one.
            val first = neighbors.removeAt(0)
            neighbors.add(first)
        }

        GoldbergFace(
            index = vi,
            centroid = toVec3(centroid),
            vertices = sortedCorners.map(::toVec3),
            neighbors = neighbors.toList(),
            isPentagon = sortedCorners.size == 5,
        )
    }

    return GoldbergMesh(faces, t)
}
