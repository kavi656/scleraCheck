package com.scleracheck.decision

import com.scleracheck.quality.QualityResult
import com.scleracheck.quality.RetakeReason

enum class Outcome { NO_CONCERN, REFER, RETAKE }

data class Decision(
    val outcome: Outcome,
    val bStar: Double?,
    val delta: Double?,
    val explanation: String,
    val retakeReasons: List<RetakeReason> = emptyList(),
)

/**
 * v1 decision rule (CLAUDE.md §5), deliberately simple and explainable:
 *   delta = b*_today − b*_baseline (b*_today = pixel-weighted mean of left and right gaze)
 *   REFER if delta > T_change on CONSECUTIVE_FOR_REFER consecutive checks (including today),
 *      OR b*_today > T_absolute.
 *   NO_CONCERN otherwise. RETAKE whenever the quality gate fails — never force a verdict.
 */
object DecisionEngine {

    /**
     * Combine the two gaze sides weighted by clean sclera pixels (a side that measured 3000 px is more
     * trustworthy than one that measured 450). Weight ratio capped at 3:1 so one side can't fully
     * hide the other.
     */
    fun weightedMean(bLeft: Double, bRight: Double, pxLeft: Int, pxRight: Int): Double {
        val l = pxLeft.coerceAtLeast(1).toDouble(); val r = pxRight.coerceAtLeast(1).toDouble()
        val wl = minOf(l, 3 * r); val wr = minOf(r, 3 * l)
        return (bLeft * wl + bRight * wr) / (wl + wr)
    }

    fun combineSides(a: com.scleracheck.measure.SideMeasurement, b: com.scleracheck.measure.SideMeasurement): Double =
        weightedMean(a.bStar, b.bStar, a.cleanPixels, b.cleanPixels)

    /**
     * @param previousDeltas deltas of earlier *valid* checks, most recent LAST (retakes excluded).
     * @param pxLeft clean pixels of each side, used to weight the two sides (weightedMean).
     */
    fun decide(
        quality: QualityResult,
        bLeft: Double?,
        bRight: Double?,
        baselineB: Double?,
        previousDeltas: List<Double>,
        pxLeft: Int = 1,
        pxRight: Int = 1,
    ): Decision {
        if (!quality.passed || bLeft == null || bRight == null) {
            return Decision(Outcome.RETAKE, null, null, "The capture wasn't good enough to give a result.", quality.reasons)
        }
        val today = weightedMean(bLeft, bRight, pxLeft, pxRight)
        if (today > Thresholds.T_ABSOLUTE) {
            return Decision(Outcome.REFER, today, baselineB?.let { today - it },
                "Yellowness index %.1f is above the safety threshold %.1f.".format(today, Thresholds.T_ABSOLUTE))
        }
        if (baselineB == null) {
            return Decision(Outcome.NO_CONCERN, today, null,
                "No baseline recorded yet, so only the safety threshold was checked.")
        }
        val delta = today - baselineB
        val needed = Thresholds.CONSECUTIVE_FOR_REFER
        val recent = previousDeltas.takeLast(needed - 1) + delta
        val consecutiveHigh = recent.size >= needed && recent.all { it > Thresholds.T_CHANGE }
        return when {
            consecutiveHigh -> Decision(Outcome.REFER, today, delta,
                "Yellowness has risen by more than %.1f from your baseline on %d checks in a row.".format(Thresholds.T_CHANGE, needed))
            delta > Thresholds.T_CHANGE -> Decision(Outcome.NO_CONCERN, today, delta,
                "Slightly higher than your baseline today. Please repeat the check tomorrow so we can confirm.")
            else -> Decision(Outcome.NO_CONCERN, today, delta, "Close to your baseline.")
        }
    }
}
