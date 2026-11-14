package com.scleracheck.decision

import kotlin.math.sqrt

/**
 * Builds a baseline from several captures instead of one (CLAUDE.md §2A.2, §5).
 *
 * A single capture carries the full check-to-check noise, and every later decision is measured
 * against the baseline, so its error would shift every future delta. Taking the median of
 * [Thresholds.BASELINE_CAPTURES] captures that agree within [Thresholds.BASELINE_MAX_SPREAD]
 * makes it robust to one bad capture, and the spread gives a per-patient noise estimate.
 * Pure Kotlin, unit-tested in BaselineBuilderTest.
 */
object BaselineBuilder {

    data class Result(
        /** Median b* of the chosen captures — the stored baseline. */
        val bStar: Double,
        /** Sample SD of the chosen captures (logged; informs T_change, CLAUDE.md §6.2). */
        val sd: Double,
        val spread: Double,
        val chosen: List<Double>,
    )

    /**
     * From all valid capture values so far, pick the [n] values that agree most closely
     * (smallest max−min window after sorting). Returns null until there are [n] values whose
     * spread is ≤ [maxSpread].
     */
    fun choose(values: List<Double>, n: Int = Thresholds.BASELINE_CAPTURES, maxSpread: Double = Thresholds.BASELINE_MAX_SPREAD): Result? {
        if (values.size < n) return null
        val s = values.sorted()
        var best: List<Double>? = null
        for (i in 0..s.size - n) {
            val w = s.subList(i, i + n)
            if (best == null || w.last() - w.first() < best.last() - best.first()) best = w
        }
        val b = best ?: return null
        val spread = b.last() - b.first()
        if (spread > maxSpread) return null
        val median = if (n % 2 == 1) b[n / 2] else (b[n / 2 - 1] + b[n / 2]) / 2
        val mean = b.average()
        val sd = if (n > 1) sqrt(b.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        return Result(median, sd, spread, b)
    }
}
