package dev.gvart.genesara.api.internal.mcp.tools.inspect

/**
 * How much detail [InspectTool] returns about a target. Driven by the calling agent's
 * Perception attribute. Tiers are deliberately coarse (3 buckets) so a 1-point swing
 * doesn't move an agent across boundaries — Perception milestones at 50/100/200 (canon)
 * are the meaningful thresholds for this attribute, not Phase-0 inspect depth.
 *
 * Numeric thresholds are tunable in playtesting (Appendix B in the mechanics spec).
 */
internal enum class InspectDepth { SHALLOW, DETAILED, EXPERT }

/**
 * Map a Perception value to an [InspectDepth]. The lookup is intentionally a single
 * helper (not a per-tool abstraction) because Phase 0 only has one Perception-gated
 * verb — extracting an interface for a single caller is premature.
 *
 * Phase-0 thresholds:
 *  - `< 5`   -> SHALLOW   (just-spawned or low-Perception agents see the basics)
 *  - `5..14` -> DETAILED  (typical mid-game distribution)
 *  - `>= 15` -> EXPERT    (specialised perception builds; gates the surfacing of
 *                         info that would otherwise be Researcher-class scanning)
 */
internal fun inspectDepthFor(perception: Int): InspectDepth = when {
    perception < 5 -> InspectDepth.SHALLOW
    perception < 15 -> InspectDepth.DETAILED
    else -> InspectDepth.EXPERT
}
