package com.scleracheck.segmentation

import com.scleracheck.colour.ColourMath
import com.scleracheck.decision.Thresholds

/**
 * Sclera segmentation: the region between the iris and the eyelid, then remove pixels that are
 * not clean sclera. Landmark-polygon segmentation is the approach used by smartphone sclera work
 * such as BiliScreen (Mariakakis et al., Proc. ACM IMWUT 2017), which also excluded vessels and glare.
 */
object ScleraSegmenter {

    class Mask(
        /** Pixel indices inside eyelid contour minus iris (before cleaning). */
        val candidate: IntArray,
        /** Clean sclera pixel indices (subset of candidate). */
        val clean: IntArray,
        val glareCount: Int,
        val vesselCount: Int,
        val darkCount: Int,
    ) {
        val glareFraction: Double get() = if (candidate.isEmpty()) 1.0 else glareCount.toDouble() / candidate.size
        val vesselFraction: Double get() = if (candidate.isEmpty()) 0.0 else vesselCount.toDouble() / candidate.size
    }

    /**
     * Candidate region:
     *  - inside the eyelid contour, shrunk to drop the lid margin and the inner-corner caruncle
     *    (the caruncle is pink tissue, not sclera);
     *  - outside a dilated iris disk (the limbus is a soft edge; dilation avoids iris pixels);
     *  - within a horizontal band level with the iris (|y − iris y| ≤ IRIS_BAND × iris radius): the
     *    nasal and temporal sclera beside the iris. The slivers above and below the iris sit in
     *    eyelid shadow and next to the tear meniscus, and change from capture to capture.
     *    Field data 14 Nov: side 1 measured the strip below the iris, side 2 above it, b* differed by 8.
     */
    fun candidate(img: RgbImage, eye: EyeGeometry): IntArray {
        val c = eye.centroid()
        val poly = PolygonUtil.shrink(eye.contour, c, Thresholds.LID_SHRINK_X, Thresholds.LID_SHRINK_Y)
        val irisR = eye.irisRadius * Thresholds.IRIS_DILATE
        val irisR2 = irisR * irisR
        val band = eye.irisRadius * Thresholds.IRIS_BAND
        val b = eye.bounds
        val x0 = b[0].coerceIn(0, img.width - 1); val x1 = b[2].coerceIn(0, img.width)
        val y0 = b[1].coerceIn(0, img.height - 1); val y1 = b[3].coerceIn(0, img.height)
        val out = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) {
            val dx = x - eye.irisCenter.x; val dy = y - eye.irisCenter.y
            if (dx * dx + dy * dy <= irisR2) continue
            if (kotlin.math.abs(dy) > band) continue
            if (PolygonUtil.contains(poly, x + 0.5f, y + 0.5f)) out.add(y * img.width + x)
        }
        return out.toIntArray()
    }

    /**
     * Clean the candidate region using the TORCH frame:
     *  - Specular glare: any channel near saturation, or luminance far above the sclera reference.
     *    The tear film is a mirror; glare carries the light's colour, not the sclera's.
     *  - Eyelashes / lid shadow: luminance far below the sclera reference.
     *  - Blood vessels: redness (R/G in linear light) well above the median R/G of the pixels that survived.
     *
     * The reference brightness is a HIGH PERCENTILE of the region, not the median: the sclera is the
     * brightest diffuse tissue around the eye, so when lashes or lid fill most of the polygon (squint,
     * close-up landmark error) the median is a lash and would make real sclera look like "glare".
     * Field data 14 Nov 2026: median-referenced masks kept lashes and discarded sclera.
     */
    fun segment(img: RgbImage, eye: EyeGeometry): Mask {
        val cand = candidate(img, eye)
        if (cand.isEmpty()) return Mask(cand, IntArray(0), 0, 0, 0)
        val lut = ColourMath.LINEAR_LUT
        val lum = DoubleArray(cand.size)
        val redness = DoubleArray(cand.size)
        for ((k, i) in cand.withIndex()) {
            val r = lut[img.r(i)]; val g = lut[img.g(i)]; val bl = lut[img.b(i)]
            lum[k] = ColourMath.luminance(r, g, bl)
            redness[k] = r / (g + 1e-4)
        }
        // Reference = percentile of NON-saturated pixels (saturated ones are glare by definition).
        val sat = BooleanArray(cand.size) { k ->
            val i = cand[k]
            img.r(i) >= Thresholds.SATURATION_8BIT || img.g(i) >= Thresholds.SATURATION_8BIT || img.b(i) >= Thresholds.SATURATION_8BIT
        }
        val unsat = lum.filterIndexed { k, _ -> !sat[k] }.toDoubleArray()
        val ref = if (unsat.isEmpty()) Double.MAX_VALUE else percentile(unsat, Thresholds.SCLERA_REF_PERCENTILE)
        var glare = 0; var dark = 0
        val keep = ArrayList<Int>(cand.size) // indices into cand
        for (k in cand.indices) {
            when {
                sat[k] || lum[k] > ref * Thresholds.GLARE_LUM_FACTOR -> glare++
                lum[k] < ref * Thresholds.DARK_LUM_FACTOR -> dark++
                else -> keep.add(k)
            }
        }
        // Second pass: vessels relative to the redness of the remaining (mostly sclera) pixels.
        val medRed = ColourMath.median(DoubleArray(keep.size) { redness[keep[it]] })
        val clean = ArrayList<Int>(keep.size)
        var vessel = 0
        for (k in keep) {
            if (redness[k] > medRed * Thresholds.VESSEL_REDNESS_FACTOR) vessel++ else clean.add(cand[k])
        }
        return Mask(cand, clean.toIntArray(), glare, vessel, dark)
    }

    fun percentile(v: DoubleArray, p: Double): Double {
        val s = v.copyOf(); s.sort()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }
}
