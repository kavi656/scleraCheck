package com.scleracheck.colour

import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Pure colour maths. No Android imports — unit-tested on the JVM (see ColourMathTest).
 *
 * Conventions: linear RGB in [0,1], sRGB primaries, D65 white.
 * References:
 *  - IEC 61966-2-1:1999 (sRGB transfer function and RGB→XYZ matrix).
 *  - CIE 15:2004 Colorimetry, CIELAB definition (L*, a*, b*), with the CIE 1976 piecewise f(t).
 */
object ColourMath {

    /** D65 reference white, Y normalised to 1 (CIE 15:2004, 2° observer). */
    const val XN = 0.95047
    const val YN = 1.00000
    const val ZN = 1.08883

    /**
     * sRGB EOTF: 8-bit-encoded value in [0,1] → linear light.
     * We assume the camera's YUV output follows the sRGB tone curve. That is an approximation
     * (vendors apply their own tone maps), but it is the same on every check with the same
     * device, and decisions are made relative to the patient's own baseline on that device.
     */
    fun srgbToLinear(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(v: Double): Double =
        if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

    /** Lookup table for 8-bit → linear, used in the hot pixel loops. */
    val LINEAR_LUT: DoubleArray = DoubleArray(256) { srgbToLinear(it / 255.0) }

    /** Linear sRGB → CIE XYZ (D65). IEC 61966-2-1 matrix. */
    fun linearRgbToXyz(r: Double, g: Double, b: Double): DoubleArray = doubleArrayOf(
        0.4124564 * r + 0.3575761 * g + 0.1804375 * b,
        0.2126729 * r + 0.7151522 * g + 0.0721750 * b,
        0.0193339 * r + 0.1191920 * g + 0.9503041 * b,
    )

    /** Relative luminance Y of linear sRGB (middle row of the matrix above). */
    fun luminance(r: Double, g: Double, b: Double): Double =
        0.2126729 * r + 0.7151522 * g + 0.0721750 * b

    private const val EPS = 216.0 / 24389.0   // (6/29)^3
    private const val KAPPA = 24389.0 / 27.0  // (29/3)^3

    private fun f(t: Double): Double = if (t > EPS) cbrt(t) else (KAPPA * t + 16.0) / 116.0

    /** CIE XYZ → CIELAB relative to D65. Returns [L*, a*, b*]. */
    fun xyzToLab(x: Double, y: Double, z: Double): DoubleArray {
        val fx = f(x / XN)
        val fy = f(y / YN)
        val fz = f(z / ZN)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun linearRgbToLab(r: Double, g: Double, b: Double): DoubleArray {
        val xyz = linearRgbToXyz(r, g, b)
        return xyzToLab(xyz[0], xyz[1], xyz[2])
    }

    /** Convenience for tests: 8-bit sRGB → Lab. */
    fun srgb8ToLab(r: Int, g: Int, b: Int): DoubleArray =
        linearRgbToLab(LINEAR_LUT[r], LINEAR_LUT[g], LINEAR_LUT[b])

    /**
     * Flash/no-flash differencing, per channel, in LINEAR light.
     * Light adds linearly in raw/linear space (Petschnigg et al., "Digital photography with flash and
     * no-flash image pairs", SIGGRAPH 2004), so torch − ambient leaves only the torch's contribution,
     * whose colour is fixed by the LED and does not change with the room's lighting.
     * Must be done with exposure and white balance LOCKED between the two bursts.
     */
    fun torchOnly(torch: DoubleArray, ambient: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> (torch[i] - ambient[i]).coerceAtLeast(0.0) }

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
}
