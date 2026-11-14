package com.scleracheck

import com.scleracheck.colour.ColourCorrection
import com.scleracheck.colour.ColourMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Known values: CIE / Bruce Lindbloom reference conversions (sRGB, D65). */
class ColourMathTest {
    private fun lab(r: Int, g: Int, b: Int) = ColourMath.srgb8ToLab(r, g, b)

    @Test fun whiteIsL100Neutral() {
        val l = lab(255, 255, 255)
        assertEquals(100.0, l[0], 0.01); assertEquals(0.0, l[1], 0.05); assertEquals(0.0, l[2], 0.05)
    }
    @Test fun blackIsZero() {
        val l = lab(0, 0, 0)
        assertEquals(0.0, l[0], 1e-9); assertEquals(0.0, l[1], 1e-9); assertEquals(0.0, l[2], 1e-9)
    }
    @Test fun pureRed() { // L 53.24, a 80.09, b 67.20
        val l = lab(255, 0, 0)
        assertEquals(53.24, l[0], 0.05); assertEquals(80.09, l[1], 0.1); assertEquals(67.20, l[2], 0.1)
    }
    @Test fun pureYellowHasLargePositiveB() { // L 97.14, a -21.55, b 94.48
        val l = lab(255, 255, 0)
        assertEquals(97.14, l[0], 0.05); assertEquals(-21.55, l[1], 0.1); assertEquals(94.48, l[2], 0.1)
    }
    @Test fun midGrey() { // sRGB 128 → L* 53.59
        assertEquals(53.59, lab(128, 128, 128)[0], 0.05)
    }
    @Test fun srgbRoundTrip() {
        for (v in listOf(0.0, 0.01, 0.04045, 0.2, 0.5, 0.9, 1.0))
            assertEquals(v, ColourMath.linearToSrgb(ColourMath.srgbToLinear(v)), 1e-6) // IEC breakpoints differ slightly (~1e-7)
    }
    @Test fun yellowerWhiteHasHigherB() {
        assertTrue(lab(250, 245, 215)[2] > lab(250, 248, 240)[2])
    }

    @Test fun differencingRemovesAmbient() {
        val ambient = doubleArrayOf(0.10, 0.08, 0.04) // warm room light
        val torchOnlyTrue = doubleArrayOf(0.40, 0.40, 0.38)
        val torchFrame = DoubleArray(3) { ambient[it] + torchOnlyTrue[it] }
        val out = ColourMath.torchOnly(torchFrame, ambient)
        for (i in 0..2) assertEquals(torchOnlyTrue[i], out[i], 1e-12)
    }
    @Test fun differencingClampsNegative() {
        val out = ColourMath.torchOnly(doubleArrayOf(0.1, 0.1, 0.1), doubleArrayOf(0.2, 0.05, 0.1))
        assertEquals(0.0, out[0], 0.0); assertEquals(0.05, out[1], 1e-12); assertEquals(0.0, out[2], 0.0)
    }

    @Test fun identityCcm() {
        val v = doubleArrayOf(0.3, 0.5, 0.7)
        val o = ColourCorrection().apply(v)
        for (i in 0..2) assertEquals(v[i], o[i], 1e-12)
    }
    @Test fun ccmFitRecoversKnownMatrix() {
        val truth = ColourCorrection(doubleArrayOf(1.2, -0.1, 0.0, 0.05, 0.9, 0.05, 0.0, -0.2, 1.3))
        val measured = listOf(
            doubleArrayOf(0.8, 0.1, 0.1), doubleArrayOf(0.1, 0.7, 0.2), doubleArrayOf(0.1, 0.2, 0.9),
            doubleArrayOf(0.5, 0.5, 0.5), doubleArrayOf(0.9, 0.8, 0.2), doubleArrayOf(0.3, 0.6, 0.8),
        )
        val ref = measured.map { truth.apply(it) }
        val fit = ColourCorrection.fit(measured, ref)
        for (i in 0..8) assertEquals(truth.m[i], fit.m[i], 1e-9)
    }
    @Test fun ccmSerialiseRoundTrip() {
        val c = ColourCorrection(doubleArrayOf(1.1, 0.0, 0.2, 0.0, 1.0, 0.0, 0.3, 0.0, 0.9))
        val back = ColourCorrection.deserialise(c.serialise())
        for (i in 0..8) assertEquals(c.m[i], back.m[i], 1e-12)
        assertEquals(1.0, ColourCorrection.deserialise("garbage").m[0], 0.0)
    }
    @Test fun medianOddEven() {
        assertEquals(2.0, ColourMath.median(doubleArrayOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, ColourMath.median(doubleArrayOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }
}
