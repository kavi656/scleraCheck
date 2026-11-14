package com.scleracheck.ui

import android.content.Context
import android.graphics.Bitmap
import com.scleracheck.decision.Decision
import com.scleracheck.measure.EyeFrame
import com.scleracheck.measure.ScleraMeasurer
import com.scleracheck.measure.SideMeasurement
import com.scleracheck.quality.QualityResult
import com.scleracheck.segmentation.ScleraSegmenter
import java.io.File
import java.io.FileOutputStream

/**
 * Developer toggle only (CLAUDE.md §9). Saves, on-device:
 *  - the cropped SCLERA patch of the sharpest torch frame for every side attempt, with every
 *    non-sclera pixel blacked out (no iris/skin/face kept — CLAUDE.md §1.4);
 *  - one CSV row per side attempt plus one per finished check, for the experiments in §6.
 * Files: /sdcard/Android/data/com.scleracheck/files/debug/ (pull with adb, see README).
 */
object PatchSaver {
    private const val HEADER = "timestamp,row,label,side,attempt,result,bStar,aStar,lStarRaw,naiveBStar,cleanPx," +
        "glare,vessel,torchRatio,sharpness,gaze,ambientFrames,torchFrames,torchOpenFrames,ev,zoom\n"

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "debug").apply { mkdirs() }

    private fun csv(ctx: Context): File {
        val f = File(dir(ctx), "log_v2.csv")
        if (!f.exists()) f.writeText(HEADER)
        return f
    }

    fun saveSide(
        ctx: Context, checkTs: Long, side: Int, attempt: Int, torch: List<EyeFrame>,
        m: SideMeasurement?, q: QualityResult, ev: Double, zoom: Float,
    ) {
        maskedPatch(torch)?.let { bmp ->
            FileOutputStream(File(dir(ctx), "${checkTs}_side${side}_try$attempt.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val result = if (q.passed) "pass" else q.reasons.joinToString("|") { it.name }
        csv(ctx).appendText(listOf(checkTs, "side", DevLabel.current, side, attempt, result,
            m?.bStar, m?.aStar, m?.lStarRaw, m?.naiveBStar, m?.cleanPixels, m?.glareFraction, m?.vesselFraction,
            m?.torchToAmbient, m?.sharpness, m?.gazeOffset, m?.ambientFrames, m?.torchFrames, m?.torchOpenFrames,
            "%.2f".format(ev), "%.2f".format(zoom)).joinToString(",") + "\n")
    }

    /** The sharpest eyes-open lit frame, cropped to the eye, with everything except clean sclera blacked out. */
    fun maskedPatch(torch: List<EyeFrame>): Bitmap? {
        val f = ScleraMeasurer.selectFrames(torch, 1).firstOrNull() ?: return null
        val mask = ScleraSegmenter.segment(f.img, f.eye)
        val out = IntArray(f.img.pixels.size) { 0xFF000000.toInt() } // opaque black
        for (i in mask.clean) out[i] = f.img.pixels[i]
        return Bitmap.createBitmap(out, f.img.width, f.img.height, Bitmap.Config.ARGB_8888)
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveCheck(ctx: Context, checkTs: Long, frames: Array<List<EyeFrame>?>, d: Decision) {
        val reasons = d.retakeReasons.joinToString("|") { it.name }
        csv(ctx).appendText(listOf(checkTs, "check", DevLabel.current, "", "", "${d.outcome}${if (reasons.isNotEmpty()) ":$reasons" else ""}",
            d.bStar, "", "", "", "", "", "", "", "", "", "", "", "", "", "").joinToString(",") + "\n")
    }
}

/** Free-text label (e.g. "alice_warm_lamp") typed in dev mode, written to the CSV for experiments. */
object DevLabel { @Volatile var current: String = "" }
