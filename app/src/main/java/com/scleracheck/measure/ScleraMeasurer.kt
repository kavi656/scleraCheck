package com.scleracheck.measure

import com.scleracheck.colour.ColourCorrection
import com.scleracheck.colour.ColourMath
import com.scleracheck.decision.Thresholds
import com.scleracheck.quality.Sharpness
import com.scleracheck.segmentation.EyeGeometry
import com.scleracheck.segmentation.RgbImage
import com.scleracheck.segmentation.ScleraSegmenter

/** One captured frame with the landmarks found in it. Pure Kotlin. */
class EyeFrame(val img: RgbImage, val eye: EyeGeometry, val timestampMs: Long) {
    val sharpness: Double by lazy { Sharpness.ofEye(img, eye) }
}

/** Result for one gaze position (look-left or look-right). */
data class SideMeasurement(
    val bStar: Double,
    val aStar: Double,
    /** L* of the torch-only signal BEFORE exposure normalisation (logged; depends on distance). */
    val lStarRaw: Double,
    val cleanPixels: Int,
    val glareFraction: Double,
    val vesselFraction: Double,
    /** Torch-only luminance / ambient luminance on the sclera. */
    val torchToAmbient: Double,
    val sharpness: Double,
    val gazeOffset: Double,
    val framesUsed: Int,
    /** "Without correction" comparison for the lighting-invariance experiment (CLAUDE.md §6.1):
     *  plain b* of the ambient-lit frame, no differencing / normalisation. Expected to drift with room light. */
    val naiveBStar: Double?,
    /** Diagnostics: frames received in each burst and how many torch frames had eyes open. */
    val ambientFrames: Int = 0,
    val torchFrames: Int = 0,
    val torchOpenFrames: Int = 0,
)

object ScleraMeasurer {

    /**
     * Pick the frames worth measuring: eyes open (no blink), sorted sharpest first.
     */
    fun selectFrames(frames: List<EyeFrame>, keep: Int = 3): List<EyeFrame> =
        frames.filter { it.eye.openness >= Thresholds.MIN_OPENNESS }
            .sortedByDescending { it.sharpness }
            .take(keep)

    /**
     * Mean linear RGB over the candidate sclera region of the ambient (torch-off) frames.
     *
     * Why region mean rather than pixel-wise subtraction: the hand moves a little between the two
     * bursts, so pixel-wise differencing would need image registration. Ambient light over a ~1 cm
     * patch of sclera is smooth (low spatial frequency), so subtracting its regional mean from each
     * torch pixel is a good approximation and is robust to small misalignment.
     */
    fun ambientMean(ambient: List<EyeFrame>): DoubleArray? {
        val lut = ColourMath.LINEAR_LUT
        val acc = DoubleArray(3); var n = 0
        for (f in ambient) {
            val m = ScleraSegmenter.segment(f.img, f.eye)
            for (i in m.clean) {
                acc[0] += lut[f.img.r(i)]; acc[1] += lut[f.img.g(i)]; acc[2] += lut[f.img.b(i)]; n++
            }
        }
        if (n == 0) return null
        return doubleArrayOf(acc[0] / n, acc[1] / n, acc[2] / n)
    }

    /**
     * Full measurement for one side:
     *   torch-only = linear(torch pixel) − ambient regional mean  (flash/no-flash differencing)
     *   → device colour correction (3×3 CCM, from GP card calibration; identity if not calibrated)
     *   → exposure normalisation (scale to fixed median Y)
     *   → CIELAB → median b* (median is robust to the residual vessels/glare that slip through).
     */
    fun measure(
        ambientFrames: List<EyeFrame>,
        torchFrames: List<EyeFrame>,
        ccm: ColourCorrection,
    ): SideMeasurement? {
        val ambSel = ambientFrames.filter { it.eye.openness >= Thresholds.MIN_OPENNESS }
        val torchOpen = torchFrames.count { it.eye.openness >= Thresholds.MIN_OPENNESS }
        val torchSel = selectFrames(torchFrames, keep = 4)
        if (torchSel.isEmpty()) {
            return SideMeasurement(Double.NaN, Double.NaN, Double.NaN, 0, 0.0, 0.0, 0.0, 0.0,
                torchFrames.firstOrNull()?.eye?.gazeOffset?.toDouble() ?: 0.0, 0, null,
                ambientFrames.size, torchFrames.size, torchOpen)
        }
        val amb = ambientMean(if (ambSel.isNotEmpty()) ambSel else ambientFrames) ?: doubleArrayOf(0.0, 0.0, 0.0)

        val lut = ColourMath.LINEAR_LUT
        val rgbs = ArrayList<DoubleArray>()
        var glare = 0.0; var vessel = 0.0
        for (f in torchSel) {
            val m = ScleraSegmenter.segment(f.img, f.eye)
            glare += m.glareFraction; vessel += m.vesselFraction
            for (i in m.clean) {
                val t = doubleArrayOf(lut[f.img.r(i)], lut[f.img.g(i)], lut[f.img.b(i)])
                rgbs.add(ccm.apply(ColourMath.torchOnly(t, amb)))
            }
        }
        val cleanPerFrame = rgbs.size / torchSel.size
        if (rgbs.isEmpty()) {
            return SideMeasurement(Double.NaN, Double.NaN, Double.NaN, 0, glare / torchSel.size,
                vessel / torchSel.size, 0.0, torchSel.first().sharpness, torchSel.first().eye.gazeOffset.toDouble(), torchSel.size, null,
                ambientFrames.size, torchFrames.size, torchOpen)
        }

        val ys = DoubleArray(rgbs.size) { ColourMath.luminance(rgbs[it][0], rgbs[it][1], rgbs[it][2]) }
        val medY = ColourMath.median(ys)
        val ambY = ColourMath.luminance(amb[0], amb[1], amb[2])
        val scale = if (medY > 1e-6) Thresholds.NORMALISED_Y / medY else 1.0

        val bs = DoubleArray(rgbs.size); val `as` = DoubleArray(rgbs.size)
        for ((k, p) in rgbs.withIndex()) {
            val lab = ColourMath.linearRgbToLab(p[0] * scale, p[1] * scale, p[2] * scale)
            `as`[k] = lab[1]; bs[k] = lab[2]
        }
        val lRaw = ColourMath.xyzToLab(0.0, medY, 0.0)[0]

        return SideMeasurement(
            bStar = ColourMath.median(bs),
            aStar = ColourMath.median(`as`),
            lStarRaw = lRaw,
            cleanPixels = cleanPerFrame,
            glareFraction = glare / torchSel.size,
            vesselFraction = vessel / torchSel.size,
            torchToAmbient = if (ambY > 1e-6) medY / ambY else 99.0,
            sharpness = torchSel.first().sharpness,
            gazeOffset = torchSel.first().eye.gazeOffset.toDouble(),
            framesUsed = torchSel.size,
            naiveBStar = selectFrames(ambSel, 1).firstOrNull()?.let { liveEstimate(it) },
            ambientFrames = ambientFrames.size,
            torchFrames = torchFrames.size,
            torchOpenFrames = torchOpen,
        )
    }

    /**
     * Quick single-frame estimate for the live developer readout (build step 5). No differencing,
     * so it WILL drift with room lighting — that's the point of showing it next to the real value.
     */
    fun liveEstimate(frame: EyeFrame): Double? {
        val m = ScleraSegmenter.segment(frame.img, frame.eye)
        if (m.clean.size < 50) return null
        val lut = ColourMath.LINEAR_LUT
        val bs = DoubleArray(m.clean.size)
        for ((k, i) in m.clean.withIndex()) {
            bs[k] = ColourMath.linearRgbToLab(lut[frame.img.r(i)], lut[frame.img.g(i)], lut[frame.img.b(i)])[2]
        }
        return ColourMath.median(bs)
    }
}
