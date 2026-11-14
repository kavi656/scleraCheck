package com.scleracheck.colour

import kotlin.math.abs

/**
 * 3×3 device colour-correction matrix (CCM), applied to linear RGB.
 *
 * Fitted by least squares from a colour reference card (GP onboarding, CLAUDE.md §2A):
 * measured linear RGB of each patch → known reference linear RGB of that patch.
 * A linear 3×3 CCM is the standard model for camera colour characterisation
 * (Finlayson et al., "Color correction using root-polynomial regression", IEEE TIP 2015 — the
 * plain linear model is their baseline and is adequate for a fixed light source such as the torch).
 *
 * Default is identity (no calibration done yet).
 */
class ColourCorrection(val m: DoubleArray = IDENTITY.copyOf()) {

    init { require(m.size == 9) }

    fun apply(rgb: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2],
        m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2],
        m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2],
    )

    fun serialise(): String = m.joinToString(",")

    companion object {
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun deserialise(s: String?): ColourCorrection {
            val parts = s?.split(",")?.mapNotNull { it.toDoubleOrNull() }
            return if (parts != null && parts.size == 9) ColourCorrection(parts.toDoubleArray()) else ColourCorrection()
        }

        /**
         * Least-squares fit: find M minimising Σ‖M·measured_i − reference_i‖².
         * Solved per output channel with the normal equations (AᵀA)x = Aᵀb, where A is N×3.
         * Needs at least 3 non-collinear patches; a 24-patch card is typical.
         */
        fun fit(measured: List<DoubleArray>, reference: List<DoubleArray>): ColourCorrection {
            require(measured.size == reference.size && measured.size >= 3) { "Need ≥3 patch pairs" }
            val ata = Array(3) { DoubleArray(3) }
            for (p in measured) for (i in 0..2) for (j in 0..2) ata[i][j] += p[i] * p[j]
            val inv = invert3(ata) ?: error("Patches are degenerate (collinear); cannot fit CCM")
            val out = DoubleArray(9)
            for (row in 0..2) {
                val atb = DoubleArray(3)
                for (k in measured.indices) for (i in 0..2) atb[i] += measured[k][i] * reference[k][row]
                for (j in 0..2) out[row * 3 + j] = inv[j][0] * atb[0] + inv[j][1] * atb[1] + inv[j][2] * atb[2]
            }
            return ColourCorrection(out)
        }

        fun invert3(a: Array<DoubleArray>): Array<DoubleArray>? {
            val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
            if (abs(det) < 1e-12) return null
            val d = 1.0 / det
            return arrayOf(
                doubleArrayOf(
                    (a[1][1] * a[2][2] - a[1][2] * a[2][1]) * d,
                    (a[0][2] * a[2][1] - a[0][1] * a[2][2]) * d,
                    (a[0][1] * a[1][2] - a[0][2] * a[1][1]) * d,
                ),
                doubleArrayOf(
                    (a[1][2] * a[2][0] - a[1][0] * a[2][2]) * d,
                    (a[0][0] * a[2][2] - a[0][2] * a[2][0]) * d,
                    (a[0][2] * a[1][0] - a[0][0] * a[1][2]) * d,
                ),
                doubleArrayOf(
                    (a[1][0] * a[2][1] - a[1][1] * a[2][0]) * d,
                    (a[0][1] * a[2][0] - a[0][0] * a[2][1]) * d,
                    (a[0][0] * a[1][1] - a[0][1] * a[1][0]) * d,
                ),
            )
        }
    }
}
