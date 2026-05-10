package dev.gvart.genesara.player.classes

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.ClassDefinition

/**
 * Scores a behavior fingerprint snapshot against the class catalog and picks
 * the top-2 candidates surfaced at the level-10 event (#33 / step 7).
 *
 * The score is a plain dot product: `score(c) = Σ snapshot[axis] * c.fingerprint[axis]`.
 * Categories absent from either side contribute 0 — agents that have only ever
 * gathered get a non-zero score for any class with a non-zero GATHER weight.
 *
 * Ties are broken by [AgentClass.ordinal] (declaration order in the enum) so
 * the outcome is fully deterministic regardless of insertion order in the
 * catalog map. Snapshot keys are `ActionCategory.name` strings — the caller in
 * `:world` stringifies its internal enum, keeping the cross-module dependency
 * uni-directional.
 */
object ClassFingerprintScorer {

    fun scoreTopTwo(
        snapshot: Map<String, Int>,
        classes: List<ClassDefinition>,
    ): List<AgentClass> = classes
        .map { it.id to score(snapshot, it.behaviorFingerprint) }
        .sortedWith(compareByDescending<Pair<AgentClass, Double>> { it.second }.thenBy { it.first.ordinal })
        .take(2)
        .map { it.first }

    private fun score(snapshot: Map<String, Int>, fingerprint: Map<String, Double>): Double =
        fingerprint.entries.sumOf { (axis, weight) -> (snapshot[axis] ?: 0) * weight }
}
