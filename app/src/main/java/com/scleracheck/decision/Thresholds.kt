package com.scleracheck.decision

/**
 * EVERY tunable number lives here, each with where it came from (CLAUDE.md §5).
 *
 * STATUS (14 Nov 2026): values marked [PLACEHOLDER] are first guesses for the demo. They must be
 * replaced with numbers from the lighting-invariance and repeatability experiments (CLAUDE.md §6)
 * before anyone relies on them. When unsure, err towards REFER (CLAUDE.md §1.2).
 */
object Thresholds {

    // ---------------- Capture / guidance ----------------

    /** Typical visible iris diameter in adults: 11.7 ± 0.5 mm (Caroline & Andre, Contact Lens
     *  Spectrum 2002). Used to estimate distance from the iris size in pixels (pinhole model). */
    const val IRIS_DIAMETER_MM = 11.7

    /** Target distance band, CLAUDE.md §0: close enough that the torch dominates, far enough to focus.
     *  Most phone main cameras have a minimum focus distance of ~7–10 cm. */
    const val DIST_MIN_MM = 100.0
    const val DIST_MAX_MM = 150.0

    /** Stay this far beyond the lens's minimum focus distance (MFD); focus is unreliable right at the limit. */
    const val FOCUS_MARGIN_MM = 20.0

    /**
     * Target band for this phone: the default 100–150 mm, pushed out if the main camera can't focus
     * that close (many phones' main lenses have an MFD of 10–15 cm). Keeps a 50 mm wide band.
     */
    fun distanceBand(minFocusMm: Double?): Pair<Double, Double> {
        val lo = maxOf(DIST_MIN_MM, (minFocusMm ?: 0.0) + FOCUS_MARGIN_MM)
        return Pair(lo, maxOf(DIST_MAX_MM, lo + 50.0))
    }

    /**
     * Zoom during capture. 2× on a phone's main sensor is a sensor crop; with a ≥12 MP sensor the crop
     * is still bigger than our 1280×960 analysis frames, so the eye gets ~4× the pixels at no quality cost.
     * Kept below 2.4× so multi-camera phones don't switch to the telephoto lens, whose close-focus and
     * colour differ from the main lens (and would change the baseline comparison).
     */
    const val ZOOM_TARGET = 2.0f

    /** Zoom in only once the eye is within this × the far edge of the target band. */
    const val ZOOM_START_FACTOR = 1.5

    /** Zoom ramp per analysis frame (~15 fps ⇒ 1× → 2× in under a second) so the face tracker can follow. */
    const val ZOOM_STEP = 0.07f

    /** If the eye is lost for this long while zoomed, drop back to 1×. After 2 such losses, stay at 1×. */
    const val ZOOM_LOST_MS = 1200L

    /** Iris centre must be within this fraction of the frame centre (normalised to half-width). */
    const val CENTRE_TOLERANCE = 0.35

    /** Must be in range, centred and still for this long before capture (CLAUDE.md §3). */
    const val STABLE_MS = 500L

    /** "Still" = iris centre within this fraction of the iris radius of its smoothed (EMA) position.
     *  Compared with a smoothed position, not the previous frame, so one jittery landmark frame
     *  doesn't reset the hold timer (field report 14 Nov: outline found but capture never fired). */
    const val STILL_MAX_MOVE = 0.35

    /** EMA weight of the newest iris position for the stillness check. */
    const val STILL_EMA_ALPHA = 0.4f

    /** Slack on the distance band, because the iris-based distance estimate is itself ~±10%
     *  (iris diameter varies 11.2–12.2 mm between adults). Generous on the far side; small on the
     *  near side, because closer than the minimum focus distance the image cannot be sharp. */
    const val DIST_SLACK_FAR = 0.10
    const val DIST_SLACK_NEAR = 0.03

    /** A manual shutter press needs an eye detected this recently (for focus point and crop). */
    const val SHUTTER_EYE_MAX_AGE_MS = 600L

    /** Wait this long for the eyes to turn before capturing anyway (the band beside the iris is
     *  visible even with a straight gaze; the gate rejects it if there's too little). */
    const val GAZE_WAIT_MS = 3000L

    /** Glare fraction above which a side counts as OVEREXPOSED (whole eye clipped white). Field data
     *  14 Nov 15:24: glare = 1.0 on every attempt at 1× zoom, close to the torch. */
    const val OVEREXPOSED_GLARE = 0.6

    /** After an overexposed attempt, the next one locks exposure this much darker (EV), down to
     *  MAX_EXTRA_DARKEN. Kept for the rest of the check so the other side starts at the right level. */
    const val OVEREXPOSED_EV_STEP = -1.0
    const val MAX_EXTRA_DARKEN = -2.0

    /** Countdown between the spoken warning and the light (blink happens before sampling). */
    const val COUNTDOWN_MS = 1500L

    /** Eye openness (lid gap / eye width) below this ⇒ blink or squint. Soukupová & Čech (2016)
     *  use EAR ≈ 0.2 for blink detection; landmark definitions differ slightly so we use 0.18. */
    const val MIN_OPENNESS = 0.18

    /** |gaze offset| (iris vs eye mid-point, fraction of half-width) needed to accept "look left/right". */
    const val MIN_GAZE_OFFSET = 0.20

    /** Eyes-open frames wanted per burst (torch off, then torch on). We keep collecting until we have
     *  this many with openness ≥ MIN_OPENNESS, because people often blink when the torch comes on
     *  (field data 14 Nov 2026: a whole 6-frame torch burst was lost to a blink). */
    const val BURST_OPEN_FRAMES = 4

    /** Hard cap on frames per burst, whatever their openness. */
    const val BURST_MAX_FRAMES = 20

    /** Ambient (torch-off) burst time limit. */
    const val AMBIENT_BURST_MS = 1500L

    /**
     * Exposure compensation applied before locking AE, in EV. With exposure metered for the room,
     * adding the torch (3.5–17× the ambient light on the sclera in field tests) clipped the bright
     * channels and washed out the yellow. −2 EV leaves headroom for the torch; the ambient frames get
     * darker but they're only used for subtraction. [PLACEHOLDER] — check glare fraction / clipping in log.csv.
     */
    const val TORCH_EXPOSURE_EV = -1.5
    // History: −2 EV (14 Nov) lost every ambient frame on the Oppo: too dark for the face tracker.
    // −1 EV plus landmark carry-over (CARRY_OVER_MS) keeps tracking while still leaving headroom.

    /** Same idea for the front camera + screen light. The screen is much dimmer than the LED
     *  (roughly the same order as indoor room light at 15–20 cm), so less headroom is needed. [PLACEHOLDER] */
    const val SCREEN_EXPOSURE_EV = -1.0

    /** Settle time after switching the screen to white: many phones animate brightness changes. */
    const val SCREEN_SETTLE_MS = 450L

    /** Attempts per gaze side before the whole check returns Retake. A failed side is re-captured
     *  automatically instead of throwing away a good capture of the other side. */
    const val MAX_SIDE_ATTEMPTS = 3

    /** Reuse the last detected eye position for this long if tracking drops during a burst: dark ambient
     *  frames AND overexposed torch frames (field data: a whole torch burst lost, 0 frames). */
    const val CARRY_OVER_MS = 1200L

    /** Lower plausible b*. The torch LED is bluish, so real torch-only readings sit near −10 on the Oppo;
     *  nonsense values seen in the field were ≈ −40 to −50. */
    const val B_PLAUSIBLE_MIN = -25.0
    const val B_PLAUSIBLE_MAX = 45.0

    // ---------------- Baseline (CLAUDE.md §2A.2) ----------------

    /** Valid captures combined into a baseline (median). */
    const val BASELINE_CAPTURES = 3

    /** Give up after this many full captures if no 3 agree. */
    const val BASELINE_MAX_CAPTURES = 6

    /** The chosen captures must agree within this b* range (max − min). [PLACEHOLDER] Set to ~2× the
     *  per-person SD from the repeatability experiment once measured; kept below T_change + 1 so a
     *  baseline can't hide a change the decision rule would flag. */
    const val BASELINE_MAX_SPREAD = 4.0

    /** Frames to discard after the torch turns on while the LED and sensor settle. */
    const val TORCH_SETTLE_MS = 250L

    /** Hard safety cap on torch-on time per burst (CLAUDE.md §1.3: 2 s max). */
    const val TORCH_MAX_MS = 2000L

    /** Torch strength as a fraction of the device max, where the device supports it (Android 15+).
     *  [PLACEHOLDER] ~60%: lower than default on most phones but still several × ambient at 12 cm.
     *  Lower it if the torch-to-ambient ratio stays comfortably above MIN_TORCH_RATIO. */
    const val TORCH_STRENGTH_FRACTION = 0.6

    // ---------------- Segmentation ----------------

    /** Shrink eyelid polygon toward its centroid to drop the lid margin / lashes / caruncle. */
    const val LID_SHRINK_X = 0.88f
    const val LID_SHRINK_Y = 0.72f

    /** Iris disk dilation to stay clear of the limbus (soft dark ring at the iris edge). */
    const val IRIS_DILATE = 1.18f

    /** Measure only sclera within ± this × iris radius of the iris centre, vertically (see ScleraSegmenter). */
    const val IRIS_BAND = 0.7f

    /** 8-bit value treated as saturated (glare). 245 not 255: camera tone curves roll off before
     *  hard clipping, and a clipped channel shifts hue (clipping R and G but not B lowers b*). */
    const val SATURATION_8BIT = 245

    /** Sclera reference brightness = this percentile of UNSATURATED candidate luminance (see ScleraSegmenter).
     *  95th so the sclera wins even when it's <20% of the polygon (squint / close-up landmark error);
     *  saturated glare is removed before the percentile so it can't become the reference. */
    const val SCLERA_REF_PERCENTILE = 0.95

    /** Pixel luminance > factor × sclera reference ⇒ specular glare. */
    const val GLARE_LUM_FACTOR = 1.35

    /** Pixel luminance < factor × sclera reference ⇒ lashes / lid shadow / iris edge. */
    const val DARK_LUM_FACTOR = 0.5

    /** Pixel R/G > factor × region median R/G ⇒ blood vessel. */
    const val VESSEL_REDNESS_FACTOR = 1.25

    // ---------------- Measurement ----------------

    /** After differencing, each eye's torch-only signal is scaled so its median luminance equals this
     *  before converting to Lab. This removes the effect of distance/torch fall-off on b* (b* grows
     *  with lightness for a fixed chromaticity), leaving a chroma-like yellowness. White sclera ⇒ L*≈90. */
    const val NORMALISED_Y = 0.75

    // ---------------- Quality gate (CLAUDE.md §4) ----------------

    /** X: min clean sclera pixels per frame (×4 frames per side). Was 300; field data 14 Nov rejected
     *  sharp, open-eye captures at 218 and 268 px after restricting to the band beside the iris. */
    const val MIN_CLEAN_PIXELS = 150

    /** Min sharpness (variance of Laplacian on 8-bit grey, eye region). Pech-Pacheco et al., ICPR 2000.
     *  [PLACEHOLDER] — tune by logging sharp vs blurred captures on the demo phone. */
    const val MIN_SHARPNESS = 25.0

    /** Torch-only luminance must be at least this × ambient luminance on the sclera. If the room
     *  (e.g. direct sunlight) swamps the torch, differencing becomes noisy. [PLACEHOLDER] */
    const val MIN_TORCH_RATIO = 0.5

    /** Y: max glare fraction of the candidate sclera region. [PLACEHOLDER] */
    const val MAX_GLARE_FRACTION = 0.30

    /** Z: only a GROSS left/right mismatch is a Retake. Field data 14 Nov: good captures on both sides
     *  differed by ~8 every time (different patch of sclera and lighting angle), so 6 rejected everything.
     *  Sides are combined pixel-weighted instead; reliability comes from the 3-capture baseline spread check. */
    const val MAX_LR_DISAGREEMENT = 15.0

    /** Vessel fraction above this ⇒ red eye / bleeding ⇒ Retake + "see GP if this persists". [PLACEHOLDER] */
    const val MAX_VESSEL_FRACTION = 0.30

    // ---------------- Decision rule (CLAUDE.md §5) ----------------

    /** T_change: Δb* from personal baseline that counts as a rise.
     *  [PLACEHOLDER] 3.0 b* units. Target = mean + 3·SD of one person's check-to-check noise from the
     *  repeatability experiment. Do NOT raise it without data — lower ⇒ more referrals (safer). */
    const val T_CHANGE = 3.0

    /** Number of consecutive checks above T_change required to refer. */
    const val CONSECUTIVE_FOR_REFER = 2

    /** T_absolute: any single check with b* above this ⇒ refer immediately, whatever the baseline.
     *  [PLACEHOLDER] 20.0. Normal sclera measures roughly b* ≈ 8–14 in published smartphone/colorimeter
     *  studies under D65-like light; 20 is a clearly yellow white. Uncalibrated devices make this
     *  approximate, which is why the baseline rule is the primary one. */
    const val T_ABSOLUTE = 20.0
}
