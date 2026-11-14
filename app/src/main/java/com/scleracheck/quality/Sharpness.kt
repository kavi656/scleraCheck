package com.scleracheck.quality

import com.scleracheck.segmentation.EyeGeometry
import com.scleracheck.segmentation.RgbImage

/**
 * Focus measure: variance of the Laplacian of the grey image (Pech-Pacheco et al.,
 * "Diatom autofocusing in brightfield microscopy: a comparative study", ICPR 2000).
 * Computed only over the eye's bounding box so the background doesn't dominate.
 */
object Sharpness {
    fun varianceOfLaplacian(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val ax = x0.coerceIn(1, img.width - 2); val bx = x1.coerceIn(2, img.width - 1)
        val ay = y0.coerceIn(1, img.height - 2); val by = y1.coerceIn(2, img.height - 1)
        if (bx <= ax || by <= ay) return 0.0
        fun grey(i: Int): Int { val p = img.pixels[i]; return ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8 }
        var sum = 0.0; var sum2 = 0.0; var n = 0
        val w = img.width
        for (y in ay until by) for (x in ax until bx) {
            val i = y * w + x
            val lap = (grey(i - 1) + grey(i + 1) + grey(i - w) + grey(i + w) - 4 * grey(i)).toDouble()
            sum += lap; sum2 += lap * lap; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    fun ofEye(img: RgbImage, eye: EyeGeometry): Double {
        val b = eye.bounds
        return varianceOfLaplacian(img, b[0], b[1], b[2], b[3])
    }
}
