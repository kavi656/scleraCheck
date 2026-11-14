package com.scleracheck.quality

import com.scleracheck.decision.Thresholds
import com.scleracheck.measure.SideMeasurement
import kotlin.math.abs

/** Why a capture must be retaken. Each has a patient-friendly instruction. (CLAUDE.md §4) */
enum class RetakeReason(val message: String) {
    NOT_DETECTED("We lost track of your eye. Hold the phone steady and keep it pointed at your eye."),
    EYES_CLOSED("Your eye closed when the light came on. Try to keep it wide open for a second after the countdown."),
    TOO_FEW_PIXELS("Not enough of the white of your eye was visible. Open your eyes wide and look further to the side."),
    BLURRY("The picture was blurry. Hold the phone still until you hear the beep."),
    TORCH_TOO_WEAK("The room is too bright for the phone's light to work. Move away from windows or dim the lights. Back camera with a helper uses the stronger torch."),
    OVEREXPOSED("The picture was too bright. Hold the phone a little further from the eye."),
    IMPLAUSIBLE("The colour reading didn't look right, usually because of the lighting. Move away from windows and try again."),
    BASELINE_INCONSISTENT("The baseline captures didn't agree closely enough. Check the lighting is steady and record the baseline again."),
    TOO_MUCH_GLARE("There was too much reflection on your eye. Tilt the phone slightly and try again."),
    SIDES_DISAGREE("The two measurements didn't match. Please try again."),
    TOO_RED("Your eye looks red. Please try again later. If the redness persists, contact your GP."),
}

data class QualityResult(val reasons: List<RetakeReason>) {
    val passed: Boolean get() = reasons.isEmpty()
}

object QualityGate {
    /** Checks that apply to one gaze side on its own (everything except left/right agreement). */
    fun evaluateSide(s: SideMeasurement?): QualityResult {
        if (s == null || s.torchFrames == 0) return QualityResult(listOf(RetakeReason.NOT_DETECTED))
        if (s.framesUsed == 0) return QualityResult(listOf(RetakeReason.EYES_CLOSED))
        // Whole eye clipped to white: say so (not "too few pixels / room too bright", which are symptoms).
        if (s.glareFraction > Thresholds.OVEREXPOSED_GLARE && s.cleanPixels < Thresholds.MIN_CLEAN_PIXELS)
            return QualityResult(listOf(RetakeReason.OVEREXPOSED))
        val r = linkedSetOf<RetakeReason>()
        if (s.vesselFraction > Thresholds.MAX_VESSEL_FRACTION) r += RetakeReason.TOO_RED
        if (s.cleanPixels < Thresholds.MIN_CLEAN_PIXELS || s.bStar.isNaN()) r += RetakeReason.TOO_FEW_PIXELS
        if (s.sharpness < Thresholds.MIN_SHARPNESS) r += RetakeReason.BLURRY
        if (s.torchToAmbient < Thresholds.MIN_TORCH_RATIO) r += RetakeReason.TORCH_TOO_WEAK
        if (s.glareFraction > Thresholds.MAX_GLARE_FRACTION) r += RetakeReason.TOO_MUCH_GLARE
        if (!s.bStar.isNaN() && (s.bStar < Thresholds.B_PLAUSIBLE_MIN || s.bStar > Thresholds.B_PLAUSIBLE_MAX)) r += RetakeReason.IMPLAUSIBLE
        return QualityResult(r.toList())
    }

    fun evaluate(left: SideMeasurement?, right: SideMeasurement?): QualityResult {
        val r = linkedSetOf<RetakeReason>()
        r += evaluateSide(left).reasons
        r += evaluateSide(right).reasons
        if (left == null || right == null) return QualityResult(r.toList())
        if (r.isEmpty() && abs(left.bStar - right.bStar) > Thresholds.MAX_LR_DISAGREEMENT) r += RetakeReason.SIDES_DISAGREE
        return QualityResult(r.toList())
    }
}
