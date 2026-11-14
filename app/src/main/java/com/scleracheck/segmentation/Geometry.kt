package com.scleracheck.segmentation

import kotlin.math.hypot

/** Pure-Kotlin image: ARGB_8888 packed ints, row-major. Converted from Bitmap at the Android edge. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }
    fun r(i: Int) = (pixels[i] shr 16) and 0xFF
    fun g(i: Int) = (pixels[i] shr 8) and 0xFF
    fun b(i: Int) = pixels[i] and 0xFF
}

data class Pt(val x: Float, val y: Float) {
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/**
 * One eye's landmarks in image pixel coordinates (upright image).
 * Built from MediaPipe Face Landmarker's 478-point mesh (468 face + 10 iris points).
 */
data class EyeGeometry(
    /** Closed eyelid contour, in order around the eye. */
    val contour: List<Pt>,
    val irisCenter: Pt,
    /** Iris radius in px. Human iris diameter ≈ 11.7 mm (Caroline & Andre 2002), used for distance. */
    val irisRadius: Float,
    val innerCorner: Pt,
    val outerCorner: Pt,
    val upperLid: Pt,
    val lowerLid: Pt,
) {
    val width: Float get() = innerCorner.dist(outerCorner)

    /** Lid opening / eye width ("eye aspect ratio", Soukupová & Čech 2016). Low value ⇒ blink. */
    val openness: Float get() = if (width > 0f) upperLid.dist(lowerLid) / width else 0f

    /**
     * Signed horizontal gaze offset of the iris centre relative to the midpoint of the corners,
     * as a fraction of half the eye width, in image x direction. ±0.25 or more ⇒ clearly looking sideways.
     */
    val gazeOffset: Float
        get() {
            val mx = (innerCorner.x + outerCorner.x) / 2f
            val half = width / 2f
            return if (half > 0f) (irisCenter.x - mx) / half else 0f
        }

    val bounds: IntArray
        get() {
            val xs = contour.map { it.x }; val ys = contour.map { it.y }
            return intArrayOf(xs.min().toInt(), ys.min().toInt(), xs.max().toInt() + 1, ys.max().toInt() + 1)
        }

    fun centroid(): Pt = Pt(contour.map { it.x }.average().toFloat(), contour.map { it.y }.average().toFloat())
}

object PolygonUtil {
    /** Even-odd ray casting point-in-polygon test. */
    fun contains(poly: List<Pt>, x: Float, y: Float): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Shrink polygon toward a centre by a factor (separately in x and y). */
    fun shrink(poly: List<Pt>, c: Pt, fx: Float, fy: Float): List<Pt> =
        poly.map { Pt(c.x + (it.x - c.x) * fx, c.y + (it.y - c.y) * fy) }
}
