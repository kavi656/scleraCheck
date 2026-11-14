package com.scleracheck

import com.scleracheck.colour.ColourCorrection
import com.scleracheck.decision.DecisionEngine
import com.scleracheck.decision.Outcome
import com.scleracheck.decision.Thresholds
import com.scleracheck.measure.EyeFrame
import com.scleracheck.measure.ScleraMeasurer
import com.scleracheck.quality.QualityGate
import com.scleracheck.quality.QualityResult
import com.scleracheck.quality.RetakeReason
import com.scleracheck.segmentation.EyeGeometry
import com.scleracheck.segmentation.Pt
import com.scleracheck.segmentation.RgbImage
import com.scleracheck.segmentation.ScleraSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PipelineTest {

    // Synthetic eye: ellipse eyelid, dark iris disk, sclera of a given linear colour, plus texture noise.
    private val w = 200; private val h = 120
    private val eye = run {
        val cx = 100f; val cy = 60f
        val contour = (0 until 16).map { k -> val t = k * 2 * Math.PI / 16; Pt(cx + 80f * cos(t).toFloat(), cy + 35f * sin(t).toFloat()) }
        EyeGeometry(contour, Pt(cx, cy), 22f, Pt(20f, cy), Pt(180f, cy), Pt(cx, cy - 35f), Pt(cx, cy + 35f))
    }

    private fun enc(v: Double) = (com.scleracheck.colour.ColourMath.linearToSrgb(v.coerceIn(0.0, 1.0)) * 255).toInt().coerceIn(0, 255)

    private fun frame(sclera: DoubleArray, light: DoubleArray, ambient: DoubleArray, seed: Int): EyeFrame {
        val rnd = Random(seed)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val dx = x - 100f; val dy = y - 60f
            val inIris = dx * dx + dy * dy < 22f * 22f
            val refl = if (inIris) doubleArrayOf(0.05, 0.03, 0.02) else sclera
            val n = 1.0 + (rnd.nextDouble() - 0.5) * 0.04
            val r = enc((refl[0] * (light[0] + ambient[0])) * n)
            val g = enc((refl[1] * (light[1] + ambient[1])) * n)
            val b = enc((refl[2] * (light[2] + ambient[2])) * n)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return EyeFrame(RgbImage(w, h, px), eye, 0)
    }

    private fun measure(sclera: DoubleArray, ambient: DoubleArray, torchPower: Double = 0.7) = run {
        val torch = doubleArrayOf(torchPower, torchPower, torchPower * 0.95)
        val none = doubleArrayOf(0.0, 0.0, 0.0)
        val amb = (0 until 3).map { frame(sclera, none, ambient, it) }
        val tor = (0 until 3).map { frame(sclera, torch, ambient, 10 + it) }
        ScleraMeasurer.measure(amb, tor, ColourCorrection())!!
    }

    @Test fun segmentationExcludesIrisAndFindsSclera() {
        val f = frame(doubleArrayOf(0.8, 0.78, 0.7), doubleArrayOf(0.7, 0.7, 0.7), doubleArrayOf(0.0, 0.0, 0.0), 1)
        val m = ScleraSegmenter.segment(f.img, f.eye)
        assertTrue("clean=${m.clean.size}", m.clean.size > 2000)
        for (i in m.clean) { val x = i % w; val y = i / w; assertTrue((x - 100) * (x - 100) + (y - 60) * (y - 60) > 22 * 22) }
        // Only the band level with the iris (no slivers above/below it in lid shadow).
        for (i in m.clean) assertTrue("y=${i / w} outside band", kotlin.math.abs(i / w - 60) <= 22 * 0.7 + 1)
    }

    @Test fun lightingInvarianceAfterDifferencing() {
        // Same eye under very different room light: differenced b* should barely move, naive b* should.
        val sclera = doubleArrayOf(0.80, 0.77, 0.66)
        val neutral = measure(sclera, doubleArrayOf(0.02, 0.02, 0.02))
        val warmLamp = measure(sclera, doubleArrayOf(0.20, 0.13, 0.05))
        val coolDay = measure(sclera, doubleArrayOf(0.10, 0.12, 0.16))
        assertTrue("warm Δ=${warmLamp.bStar - neutral.bStar}", abs(warmLamp.bStar - neutral.bStar) < 1.5)
        assertTrue("cool Δ=${coolDay.bStar - neutral.bStar}", abs(coolDay.bStar - neutral.bStar) < 1.5)
        assertTrue("naive should drift", abs(warmLamp.naiveBStar!! - coolDay.naiveBStar!!) > 5)
    }

    @Test fun exposureNormalisationRemovesDistanceEffect() {
        val sclera = doubleArrayOf(0.80, 0.77, 0.66)
        val near = measure(sclera, doubleArrayOf(0.05, 0.05, 0.05), torchPower = 0.8)
        val far = measure(sclera, doubleArrayOf(0.05, 0.05, 0.05), torchPower = 0.45)
        assertTrue("Δ=${near.bStar - far.bStar}", abs(near.bStar - far.bStar) < 1.0)
    }

    @Test fun yellowerScleraGivesHigherIndexMonotonically() {
        val amb = doubleArrayOf(0.05, 0.05, 0.05)
        val bs = listOf(0.74, 0.68, 0.60, 0.52).map { blue -> measure(doubleArrayOf(0.80, 0.77, blue), amb).bStar }
        for (i in 1 until bs.size) assertTrue("$bs", bs[i] > bs[i - 1])
    }

    @Test fun qualityGateFlagsBrightRoom() {
        val m = measure(doubleArrayOf(0.8, 0.77, 0.66), doubleArrayOf(0.9, 0.9, 0.9), torchPower = 0.1)
        val q = QualityGate.evaluate(m, m)
        assertTrue(q.reasons.toString(), RetakeReason.TORCH_TOO_WEAK in q.reasons)
    }

    @Test fun qualityGateMissingSide() {
        assertEquals(listOf(RetakeReason.NOT_DETECTED), QualityGate.evaluate(null, null).reasons)
    }

    private val ok = QualityResult(emptyList())

    @Test fun retakeNeverForcesVerdict() {
        val d = DecisionEngine.decide(QualityResult(listOf(RetakeReason.BLURRY)), 10.0, 10.0, 10.0, emptyList())
        assertEquals(Outcome.RETAKE, d.outcome)
    }
    @Test fun singleRiseIsNotReferralButTwoAre() {
        val base = 10.0; val high = base + Thresholds.T_CHANGE + 1
        assertEquals(Outcome.NO_CONCERN, DecisionEngine.decide(ok, high, high, base, emptyList()).outcome)
        assertEquals(Outcome.REFER, DecisionEngine.decide(ok, high, high, base, listOf(Thresholds.T_CHANGE + 0.5)).outcome)
        assertEquals(Outcome.NO_CONCERN, DecisionEngine.decide(ok, high, high, base, listOf(Thresholds.T_CHANGE + 0.5, 0.2)).outcome)
    }
    @Test fun absoluteThresholdRefersImmediately() {
        val v = Thresholds.T_ABSOLUTE + 0.5
        assertEquals(Outcome.REFER, DecisionEngine.decide(ok, v, v, null, emptyList()).outcome)
        assertEquals(Outcome.REFER, DecisionEngine.decide(ok, v, v, v, emptyList()).outcome)
    }
    @Test fun nearBaselineIsNoConcern() {
        assertEquals(Outcome.NO_CONCERN, DecisionEngine.decide(ok, 10.5, 11.0, 10.0, listOf(5.0)).outcome)
    }

    /** Field failure 14 Nov 2026: lashes filled most of the polygon and the median-based mask kept
     *  lashes and threw the sclera away as "glare". Sclera must win even when it's a minority. */
    @Test fun lashDominatedRegionStillKeepsSclera() {
        val sclera = doubleArrayOf(0.80, 0.77, 0.66); val lash = doubleArrayOf(0.04, 0.03, 0.03)
        val px = IntArray(w * h) { i ->
            val y = i / w
            // Lashes cover the upper two-thirds of the measured band (y 45–75), sclera the lower third.
            val refl = if (y < 66) lash else sclera
            val r = enc(refl[0] * 0.7); val g = enc(refl[1] * 0.7); val b = enc(refl[2] * 0.7)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val img = RgbImage(w, h, px)
        val m = ScleraSegmenter.segment(img, eye)
        assertTrue("clean=${m.clean.size}", m.clean.size > 300)
        for (i in m.clean) assertTrue("lash pixel kept at y=${i / w}", i / w >= 66)
    }

    @Test fun blinkedBurstGivesEyesClosedReason() {
        val closedEye = eye.copy(upperLid = Pt(100f, 59f), lowerLid = Pt(100f, 61f))
        val f = frame(doubleArrayOf(0.8, 0.77, 0.66), doubleArrayOf(0.7, 0.7, 0.7), doubleArrayOf(0.02, 0.02, 0.02), 3)
        val closed = EyeFrame(f.img, closedEye, 0)
        val m = ScleraMeasurer.measure(listOf(f), listOf(closed, closed), ColourCorrection())
        assertEquals(listOf(RetakeReason.EYES_CLOSED), QualityGate.evaluateSide(m).reasons)
    }
}
