package com.scleracheck.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.scleracheck.camera.CameraController
import com.scleracheck.data.AppDb
import com.scleracheck.data.CaptureMode
import com.scleracheck.data.CheckRecord
import com.scleracheck.data.Prefs
import com.scleracheck.decision.Decision
import com.scleracheck.decision.DecisionEngine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import com.scleracheck.decision.BaselineBuilder
import com.scleracheck.decision.Outcome
import com.scleracheck.decision.Thresholds
import com.scleracheck.guidance.AmbientLight
import com.scleracheck.guidance.Guide
import com.scleracheck.measure.EyeFrame
import com.scleracheck.measure.ScleraMeasurer
import com.scleracheck.measure.SideMeasurement
import com.scleracheck.quality.QualityGate
import com.scleracheck.quality.QualityResult
import com.scleracheck.quality.RetakeReason
import com.scleracheck.segmentation.EyeGeometry
import com.scleracheck.segmentation.Pt
import com.scleracheck.segmentation.RgbImage
import com.scleracheck.vision.FaceLandmarkTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

sealed interface CheckPhase {
    data object Loading : CheckPhase
    data class Intro(val lightAdvice: String?) : CheckPhase
    data class Guiding(val step: Int, val text: String) : CheckPhase
    data class Capturing(val step: Int) : CheckPhase
    data object Processing : CheckPhase
    data class Finished(
        val decision: Decision,
        val first: SideMeasurement?,
        val second: SideMeasurement?,
        val isBaseline: Boolean,
        /** Developer mode only: masked sclera patch actually measured for each side (null if none). */
        val patches: List<Bitmap?> = emptyList(),
        val attempts: List<Int> = emptyList(),
    ) : CheckPhase
}

/**
 * What auto-capture is waiting for, shown as a checklist on the capture screen (everyone, not
 * just developer mode) so it's never a mystery why it hasn't captured yet.
 */
data class Readiness(
    val stage: Stage = Stage.IDLE,
    val eyeFound: Boolean = false,
    val distanceMm: Double? = null,
    val distMinMm: Double = 0.0,
    val distMaxMm: Double = 0.0,
    val centred: Boolean = false,
    val still: Boolean = false,
    val zoomOk: Boolean = false,
    val gazeOk: Boolean = false,
    val gazeDir: String = "left",
    /** 0..1 progress of the hold-still timer once everything else is OK. */
    val hold: Float = 0f,
) {
    enum class Stage { IDLE, POSITION, GAZE }
    val distOk: Boolean get() = distanceMm != null &&
        distanceMm >= distMinMm * (1 - Thresholds.DIST_SLACK_NEAR) && distanceMm <= distMaxMm * (1 + Thresholds.DIST_SLACK_FAR)
}

/** Live readout for the on-screen helper text and the developer overlay. */
data class LiveInfo(
    val eye: EyeGeometry?,
    val imgW: Int,
    val imgH: Int,
    val distanceMm: Double?,
    val sharpness: Double?,
    val liveB: Double?,
    val gaze: Float?,
)

/** One check session; owned by CheckScreen and disposed when the screen leaves composition. */
class CheckViewModel(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs = Prefs(app)
    val mode: CaptureMode = prefs.captureMode
    val front = mode == CaptureMode.FRONT_SCREEN

    /** Front mode uses the screen as the light: BLACK for the ambient burst, WHITE (full brightness) for the lit burst. */
    enum class ScreenLight { NORMAL, BLACK, WHITE }
    val screenLight = MutableStateFlow(ScreenLight.NORMAL)
    private val dao = AppDb.get(app).dao()
    val devMode = prefs.devMode
    private val guide = Guide(app)
    private val light = AmbientLight(app).also { it.start() }
    private var tracker: FaceLandmarkTracker? = null

    val phase = MutableStateFlow<CheckPhase>(CheckPhase.Loading)
    val live = MutableStateFlow(LiveInfo(null, 0, 0, null, null, null, null))
    val readiness = MutableStateFlow(Readiness())

    /** Set by the shutter (button or volume key): skip the current wait (position or gaze). */
    @Volatile private var skipRequested = false
    @Volatile private var irisEma: Pt? = null
    /** Extra exposure darkening learned from overexposed attempts in this check (≤ 0 EV). */
    private var evAdjust = 0.0

    private var camera: CameraController? = null
    private var focalPx: Double? = null
    private var caps: CameraController.Caps? = null

    /** Target distance band, widened at runtime to stay beyond the lens's minimum focus distance. */
    @Volatile var distMinMm = Thresholds.DIST_MIN_MM; private set
    @Volatile var distMaxMm = Thresholds.DIST_MAX_MM; private set

    @Volatile private var smoothDist: Double? = null
    @Volatile private var lastFocusSetMm: Double? = null
    @Volatile private var lastAfTriggerAt = 0L

    // Zoom state (analysis thread)
    private var zoomCmd = 1f
    private var zoomLosses = 0
    private var zoomDisabled = false
    private var lastEyeSeenAt = 0L
    var isBaseline = false; private set
    private var job: Job? = null

    // --- State shared with the analysis thread ---
    @Volatile private var targetEyeIndex: Int? = null
    @Volatile private var lastIdx: Int = 0
    @Volatile private var positioned = false
    @Volatile private var stableSince: Long? = null
    @Volatile private var lastIris: Pt? = null
    @Volatile private var lastEye: EyeGeometry? = null
    /** Last DETECTED eye and when, for landmark carry-over during the dark ambient burst. */
    @Volatile private var lastGoodEye: EyeGeometry? = null
    @Volatile private var lastGoodEyeAt = 0L
    @Volatile private var carryOverAllowed = false
    @Volatile private var lastStillAt = 0L
    @Volatile private var guiding = false
    @Volatile private var sink: Channel<EyeFrame>? = null
    private var frameCount = 0

    init {
        scope.launch(Dispatchers.Default) {
            tracker = runCatching { FaceLandmarkTracker(app) }.onFailure { Log.e(TAG, "landmarker", it) }.getOrNull()
            phase.value = CheckPhase.Intro(null)
            // Give the light sensor a moment to report.
            delay(600)
            phase.value = CheckPhase.Intro(light.advice())
        }
    }

    fun configure(baseline: Boolean) { isBaseline = baseline }

    private val shutterListener: () -> Unit = { scope.launch { shutter() } }
    init { Shutter.listener = shutterListener }

    fun attachCamera(c: CameraController, caps: CameraController.Caps) {
        camera = c
        focalPx = caps.focalPx
        this.caps = caps
        val (lo, hi) = Thresholds.distanceBand(caps.minFocusMm)
        distMinMm = lo; distMaxMm = hi
        Log.i(TAG, "distance band ${lo.toInt()}–${hi.toInt()} mm (min focus ${caps.minFocusMm?.toInt()} mm)")
        c.onFrame = ::onFrame
    }

    // ---------------------------------------------------------------- analysis thread

    private fun onFrame(bmp: Bitmap, ts: Long) {
        val t = tracker ?: return
        val eyes = t.detect(bmp, ts)
        val now = SystemClock.elapsedRealtime()
        val w = bmp.width; val h = bmp.height

        val eye = eyes?.let { list ->
            val idx = targetEyeIndex ?: list.indices.minByOrNull { i ->
                hypot(list[i].irisCenter.x / w - 0.5f, list[i].irisCenter.y / h - 0.5f)
            }!!
            lastIdx = idx
            list[idx]
        }
        lastEye = eye
        if (eye != null) { lastGoodEye = eye; lastGoodEyeAt = now }

        // Distance from iris size (pinhole camera: Z = f·D / d). Fallback f ≈ 0.78 × long side
        // (typical ~26 mm-equivalent main camera, ~65° FOV across the long side).
        // Zoom multiplies the effective focal length.
        val f = (focalPx ?: (0.78 * maxOf(w, h))) * (camera?.currentZoom ?: 1f)
        val dist = eye?.let { f * Thresholds.IRIS_DIAMETER_MM / (2.0 * it.irisRadius) }

        // Stillness: distance of the iris centre from its smoothed (EMA) position, relative to iris radius.
        val ema = irisEma
        val still = eye != null && ema != null && ema.dist(eye.irisCenter) / eye.irisRadius < Thresholds.STILL_MAX_MOVE
        irisEma = if (eye == null) null else if (ema == null) eye.irisCenter else {
            val a = Thresholds.STILL_EMA_ALPHA
            Pt((1 - a) * ema.x + a * eye.irisCenter.x, (1 - a) * ema.y + a * eye.irisCenter.y)
        }
        lastIris = eye?.irisCenter
        if (still) { if (lastStillAt == 0L) lastStillAt = now } else lastStillAt = 0L

        // Collect burst frames (crop to the eye only: CLAUDE.md §1.4, never keep the full face).
        val s = sink
        if (s != null) {
            // Dark ambient frames (−1 EV, or the black screen in front mode) can drop face tracking;
            // reuse the eye position from a moment ago rather than losing the whole burst.
            val e = eye ?: lastGoodEye?.takeIf { carryOverAllowed && now - lastGoodEyeAt <= Thresholds.CARRY_OVER_MS }
            if (e != null) s.trySend(cropEye(bmp, e, now))
        }

        val dev = devMode
        frameCount++
        var sharp: Double? = null; var liveB: Double? = null
        if (dev && eye != null && frameCount % 4 == 0) {
            val ef = cropEye(bmp, eye, now)
            sharp = ef.sharpness
            liveB = ScleraMeasurer.liveEstimate(ef)
        }
        val prev = live.value
        live.value = LiveInfo(eye, w, h, dist, sharp ?: prev.sharpness, liveB ?: prev.liveB, eye?.gazeOffset)

        if (guiding || phase.value is CheckPhase.Intro) driveFocus(eye, dist, w, h, now)
        if (guiding) driveZoom(eye, now)
        if (guiding) positionCue(eye, dist, still, w, h, now)
        else if (readiness.value.stage == Readiness.Stage.GAZE) {
            readiness.value = readiness.value.copy(eyeFound = eye != null, still = still, distanceMm = dist)
        }
    }

    /**
     * Keep the eye in focus while the patient moves the phone in.
     *  - Manual-focus phones: put the lens at the iris-estimated distance (smoothed), so focus follows
     *    the eye without autofocus hunting.
     *  - Others: re-run a small-spot AF scan on the eye every ~2.5 s while near the target range,
     *    because continuous AF at close range tends to settle on the lashes, brow or background.
     */
    private fun driveFocus(eye: EyeGeometry?, dist: Double?, w: Int, h: Int, now: Long) {
        val cam = camera ?: return
        val c = caps ?: return
        if (eye == null || dist == null) { smoothDist = null; return }
        val sd = smoothDist?.let { 0.6 * it + 0.4 * dist } ?: dist
        smoothDist = sd
        if (c.manualFocus) {
            val last = lastFocusSetMm
            if (last == null || abs(sd - last) / last > 0.05) {
                cam.setFocusDistanceMm(sd)
                lastFocusSetMm = sd
            }
        } else if (now - lastAfTriggerAt > 2500 && sd < distMaxMm * 1.8) {
            lastAfTriggerAt = now
            cam.focusOnEye(eye.irisCenter.x / w, eye.irisCenter.y / h)
        }
    }

    /**
     * Acquire the eye at 1× (wide view, the face tracker sees the whole face), then ramp to
     * ZOOM_TARGET as the patient nears the target distance. Small steps let MediaPipe's frame-to-frame
     * tracking follow the scale change. If tracking is lost while zoomed, fall back to 1×.
     */
    private fun driveZoom(eye: EyeGeometry?, now: Long) {
        val cam = camera ?: return
        val c = caps ?: return
        val target = minOf(Thresholds.ZOOM_TARGET, c.maxZoom)
        if (target <= 1.05f) return
        if (eye != null) lastEyeSeenAt = now
        val sd = smoothDist
        if (eye == null) {
            if (zoomCmd > 1f && now - lastEyeSeenAt > Thresholds.ZOOM_LOST_MS) {
                zoomCmd = 1f; cam.setZoom(1f)
                zoomLosses++
                if (zoomLosses >= 2) { zoomDisabled = true; Log.i(TAG, "zoom disabled: tracker lost the eye twice at zoom") }
            }
            return
        }
        if (zoomDisabled) return
        if (sd != null && sd < distMaxMm * Thresholds.ZOOM_START_FACTOR && zoomCmd < target) {
            zoomCmd = minOf(target, zoomCmd + Thresholds.ZOOM_STEP)
            cam.setZoom(zoomCmd)
        }
    }

    private fun positionCue(eye: EyeGeometry?, dist: Double?, still: Boolean, w: Int, h: Int, now: Long) {
        if (!guiding) return
        val step = (phase.value as? CheckPhase.Guiding)?.step ?: 0
        // Only while still positioning: a frame in flight must not overwrite the gaze-stage checklist.
        fun setReady(x: Readiness) { if (guiding) readiness.value = x }
        fun show(text: String, speak: Boolean = true) {
            if (!guiding) return // the flow has moved on; don't overwrite its phase from this thread
            phase.value = CheckPhase.Guiding(step, text)
            if (speak) guide.cue(text)
        }
        val target = minOf(Thresholds.ZOOM_TARGET, caps?.maxZoom ?: 1f)
        val zoomOk = zoomDisabled || target <= 1.05f || abs((camera?.currentZoom ?: 1f) - target) < 0.05f
        var r = Readiness(Readiness.Stage.POSITION, eye != null, dist, distMinMm, distMaxMm, zoomOk = zoomOk)
        if (eye == null || dist == null) {
            stableSince = null
            setReady(r)
            show(if (front) "Hold the phone like a selfie so your face is on the screen."
                 else "Point the back camera at the eye. Start at arm's length, then bring it closer.")
            return
        }
        if (targetEyeIndex == null) {
            // Lock onto whichever eye is nearest the centre for the rest of this check.
            targetEyeIndex = lastIdx
        }
        val offX = (eye.irisCenter.x / w - 0.5f) / 0.5f
        val offY = (eye.irisCenter.y / h - 0.5f) / 0.5f
        val centred = abs(offX) <= Thresholds.CENTRE_TOLERANCE && abs(offY) <= Thresholds.CENTRE_TOLERANCE
        r = r.copy(centred = centred, still = still)
        val msg = when {
            dist > distMaxMm * (1 + Thresholds.DIST_SLACK_FAR) -> "Closer"
            dist < distMinMm * (1 - Thresholds.DIST_SLACK_NEAR) -> "A bit further away"
            !centred -> if (front) "Put your eye in the middle of the screen" else "Keep the camera in front of the eye"
            !still -> "Hold still"
            !zoomOk -> "Hold still, zooming in"
            else -> null
        }
        if (msg != null) {
            stableSince = null
            setReady(r)
            show(msg)
        } else {
            val since = stableSince ?: now.also { stableSince = it }
            setReady(r.copy(hold = ((now - since).toFloat() / Thresholds.STABLE_MS).coerceIn(0f, 1f)))
            show("Good. Hold still…", speak = false)
            if (now - since >= Thresholds.STABLE_MS) positioned = true
        }
    }

    /**
     * Manual shutter (on-screen button or volume key). Skips the current wait — positioning, then
     * gaze — but never the quality gate: a bad capture still comes back as Retake with a reason.
     * Needs a recently detected eye, because focus and the crop are placed on it.
     */
    fun shutter() {
        val st = readiness.value.stage
        if (st == Readiness.Stage.IDLE) return
        if (SystemClock.elapsedRealtime() - lastGoodEyeAt > Thresholds.SHUTTER_EYE_MAX_AGE_MS) {
            guide.say(if (front) "I can't see your eye yet." else "I can't see the eye yet.")
            return
        }
        guide.tick()
        skipRequested = true
    }

    private fun cropEye(bmp: Bitmap, eye: EyeGeometry, ts: Long): EyeFrame {
        val b = eye.bounds
        val m = (eye.width * 0.2f).toInt()
        val x0 = (b[0] - m).coerceIn(0, bmp.width - 1)
        val y0 = (b[1] - m).coerceIn(0, bmp.height - 1)
        val x1 = (b[2] + m).coerceIn(x0 + 1, bmp.width)
        val y1 = (b[3] + m).coerceIn(y0 + 1, bmp.height)
        val cw = x1 - x0; val ch = y1 - y0
        val px = IntArray(cw * ch)
        bmp.getPixels(px, 0, cw, x0, y0, cw, ch)
        fun sh(p: Pt) = Pt(p.x - x0, p.y - y0)
        val e = EyeGeometry(eye.contour.map(::sh), sh(eye.irisCenter), eye.irisRadius, sh(eye.innerCorner),
            sh(eye.outerCorner), sh(eye.upperLid), sh(eye.lowerLid))
        return EyeFrame(RgbImage(cw, ch, px), e, ts)
    }

    // ---------------------------------------------------------------- check flow

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runCheck() }
    }

    /** One full capture: both gaze sides, each retried on its own if it fails. */
    private class Capture(
        val results: Array<SideMeasurement?>,
        val frames: Array<List<EyeFrame>?>,
        val attempts: IntArray,
        val quality: QualityResult,
    ) {
        val bMean: Double? get() = if (quality.passed) DecisionEngine.combineSides(results[0]!!, results[1]!!) else null
    }

    private suspend fun captureBothSides(cam: CameraController, checkTs: Long): Capture? {
        val results = arrayOfNulls<SideMeasurement>(2)
        val frames = arrayOfNulls<List<EyeFrame>>(2)
        var firstGazeSign = 0f
        val attempts = intArrayOf(0, 0)
        targetEyeIndex = null
        for (step in 0..1) {
            // A failed side is re-captured on its own (up to MAX_SIDE_ATTEMPTS) instead of
            // discarding the whole check: field data showed one good side + one blinked side.
            for (attempt in 1..Thresholds.MAX_SIDE_ATTEMPTS) {
                // The check's own job (not the session scope) is what Cancel cancels.
                if (!currentCoroutineContext().isActive) return null
                attempts[step] = attempt
                val (m, torch) = captureSide(cam, step, firstGazeSign)
                val q = QualityGate.evaluateSide(m)
                results[step] = m; frames[step] = torch
                if (RetakeReason.OVEREXPOSED in q.reasons) {
                    evAdjust = maxOf(Thresholds.MAX_EXTRA_DARKEN, evAdjust + Thresholds.OVEREXPOSED_EV_STEP)
                    Log.i(TAG, "overexposed: next attempt darker, evAdjust=$evAdjust")
                }
                if (devMode) withContext(Dispatchers.IO) {
                    runCatching { PatchSaver.saveSide(app, checkTs, step, attempt, torch, m, q, cam.appliedEvForLog, cam.currentZoom) }
                }
                if (q.passed) break
                Log.i(TAG, "side $step attempt $attempt failed: ${q.reasons}")
                if (attempt < Thresholds.MAX_SIDE_ATTEMPTS) {
                    guide.say("Let's do that side again. " + q.reasons.first().message)
                    phase.value = CheckPhase.Guiding(step, "Again: " + q.reasons.first().message)
                    delay(3500)
                }
            }
            if (step == 0) firstGazeSign = sign(results[0]?.gazeOffset?.toFloat() ?: lastEye?.gazeOffset ?: 0f)
        }
        return Capture(results, frames, attempts, QualityGate.evaluate(results[0], results[1]))
    }

    private suspend fun runCheck() {
        val cam = camera ?: return
        if (front) {
            guide.say("Hold the phone like a selfie, about a hand's width from your face, with one eye in the middle of the screen. The screen will flash white briefly.")
        } else {
            guide.say("A light on the back of the phone will turn on briefly. Point the back camera at the eye from about a hand's width away.")
        }
        delay(4500)
        if (isBaseline) runBaseline(cam) else runSingleCheck(cam)
    }

    private suspend fun runSingleCheck(cam: CameraController) {
        val checkTs = System.currentTimeMillis()
        val c = captureBothSides(cam, checkTs) ?: return
        phase.value = CheckPhase.Processing
        val baseline = dao.baseline(mode.code)
        val prevDeltas = if (baseline == null) emptyList() else dao.validChecks(mode.code)
            .filter { it.timestamp > baseline.timestamp && it.bMean != null }
            .map { it.bMean!! - baseline.bStar }
        val decision = DecisionEngine.decide(c.quality, c.results[0]?.bStar, c.results[1]?.bStar, baseline?.bStar, prevDeltas,
            c.results[0]?.cleanPixels ?: 1, c.results[1]?.cleanPixels ?: 1)
        saveRecord(decision, c.results[0], c.results[1], c.quality)
        finish(checkTs, c, decision)
    }

    /**
     * Baseline = median of BASELINE_CAPTURES valid captures that agree within BASELINE_MAX_SPREAD
     * (BaselineBuilder). Keeps capturing (up to BASELINE_MAX_CAPTURES) until that holds.
     */
    private suspend fun runBaseline(cam: CameraController) {
        val values = ArrayList<Double>()
        var last: Capture? = null
        var built: BaselineBuilder.Result? = null
        val need = Thresholds.BASELINE_CAPTURES
        for (n in 1..Thresholds.BASELINE_MAX_CAPTURES) {
            val checkTs = System.currentTimeMillis()
            phase.value = CheckPhase.Guiding(0, "Baseline capture $n (need $need that agree)")
            val c = captureBothSides(cam, checkTs) ?: return
            last = c
            val d = DecisionEngine.decide(c.quality, c.results[0]?.bStar, c.results[1]?.bStar, null, emptyList(),
                c.results[0]?.cleanPixels ?: 1, c.results[1]?.cleanPixels ?: 1)
            saveRecord(d, c.results[0], c.results[1], c.quality)
            if (devMode) withContext(Dispatchers.IO) { runCatching { PatchSaver.saveCheck(app, checkTs, c.frames, d) } }
            c.bMean?.let { values += it }
            built = BaselineBuilder.choose(values)
            Log.i(TAG, "baseline capture $n: ${c.bMean} values=$values built=$built")
            if (built != null) break
            if (n < Thresholds.BASELINE_MAX_CAPTURES) {
                val msg = if (c.quality.passed) "Good. Capture ${values.size} done. One more, please." else
                    "That one didn't work: ${c.quality.reasons.first().message} Let's go again."
                guide.say(msg)
                phase.value = CheckPhase.Guiding(0, msg)
                delay(3500)
            }
        }
        val c = last ?: return
        phase.value = CheckPhase.Processing
        val b = built
        val decision = if (b != null) {
            Decision(Outcome.NO_CONCERN, b.bStar, null,
                "Baseline from ${b.chosen.size} captures: " + b.chosen.joinToString(", ") { "%.1f".format(it) } +
                    " (spread %.1f, SD %.1f).".format(b.spread, b.sd))
        } else {
            val reasons = if (values.size >= Thresholds.BASELINE_CAPTURES) listOf(RetakeReason.BASELINE_INCONSISTENT)
                else c.quality.reasons.ifEmpty { listOf(RetakeReason.NOT_DETECTED) }
            Decision(Outcome.RETAKE, null, null, "Couldn't build a consistent baseline.", reasons)
        }
        baselineBuilt = b
        finish(System.currentTimeMillis(), c, decision)
    }

    /** Result of the last baseline run, used when the clinician confirms it. */
    var baselineBuilt: BaselineBuilder.Result? = null; private set

    private suspend fun finish(checkTs: Long, c: Capture, decision: Decision) {
        if (devMode && !isBaseline) withContext(Dispatchers.IO) {
            runCatching { PatchSaver.saveCheck(app, checkTs, c.frames, decision) }
        }
        if (isBaseline && decision.outcome != Outcome.RETAKE) guide.say("Baseline captured. Please look at the screen.")
        else speakResult(decision)
        val patches = if (devMode) withContext(Dispatchers.Default) {
            c.frames.map { f -> f?.let { runCatching { PatchSaver.maskedPatch(it) }.getOrNull() } }
        } else emptyList()
        phase.value = CheckPhase.Finished(decision, c.results[0], c.results[1], isBaseline, patches, c.attempts.toList())
    }

    /** Position → gaze → lock → torch-off burst → countdown → torch-on burst → measure, for one side. */
    private suspend fun captureSide(cam: CameraController, step: Int, firstGazeSign: Float): Pair<SideMeasurement?, List<EyeFrame>> {
        // 1) Position: distance, centred, still for STABLE_MS — or the shutter.
        positioned = false; stableSince = null; skipRequested = false
        phase.value = CheckPhase.Guiding(step, "Getting ready…")
        guiding = true
        try {
            while (!positioned && !skipRequested) delay(50)
        } finally { guiding = false }
        skipRequested = false

        // 2) Gaze: look left (first) / right (second).
        val dir = if (step == 0) "left" else "right"
        phase.value = CheckPhase.Guiding(step, "Look $dir")
        readiness.value = readiness.value.copy(stage = Readiness.Stage.GAZE, gazeDir = dir, gazeOk = false, hold = 0f)
        guide.say("Look $dir, and keep your eyes wide open.")
        // Time to move the eyes; the shutter can cut this short.
        val gazeStart = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - gazeStart < 1800 && !skipRequested) delay(50)
        // Auto when the gaze is clearly to the side and the eye is still; shutter or 4 s timeout otherwise
        // (the quality gate then judges whether enough sclera was visible).
        withTimeoutOrNull(Thresholds.GAZE_WAIT_MS) {
            while (!skipRequested) {
                val g = lastEye?.gazeOffset ?: 0f
                val okDir = step == 0 || firstGazeSign == 0f || sign(g) == -firstGazeSign
                val ok = abs(g) >= Thresholds.MIN_GAZE_OFFSET && okDir
                readiness.value = readiness.value.copy(gazeOk = ok)
                if (ok && lastStillAt != 0L) break
                guide.cue("Look further $dir", 2500)
                delay(80)
            }
        }
        skipRequested = false
        readiness.value = Readiness() // IDLE: shutter disabled while capturing

        // 3) Lock focus / exposure / white balance, then torch-off burst, then torch-on burst.
        phase.value = CheckPhase.Capturing(step)
        guide.say("Hold still")
        val e = lastEye
        val (nx, ny) = if (e != null) Pair(e.irisCenter.x / cam.analysisW, e.irisCenter.y / cam.analysisH) else Pair(0.5f, 0.5f)
        val torch: List<EyeFrame>
        val ambient: List<EyeFrame>
        val settle = if (front) Thresholds.SCREEN_SETTLE_MS else Thresholds.TORCH_SETTLE_MS
        try {
            cam.focusAndLock(nx, ny, evAdjust)
            // Warn right before the light so the reflex blink happens before we sample (CLAUDE.md §1.3:
            // tell the patient beforehand that a light will turn on).
            guide.say(if (front) "Flash coming. Eyes wide." else "Light coming. Eyes wide.")
            delay(Thresholds.COUNTDOWN_MS)
            try {
                // Ambient burst directly before the lit burst, so the room light is the same in both.
                // Front mode: black screen, so the only light is the room's.
                if (front) { screenLight.value = ScreenLight.BLACK; delay(200) }
                carryOverAllowed = true
                ambient = try {
                    collectOpen(Thresholds.BURST_OPEN_FRAMES, Thresholds.AMBIENT_BURST_MS)
                } finally { carryOverAllowed = false }
                if (front) screenLight.value = ScreenLight.WHITE else cam.setTorch(true)
                delay(settle)
                // Light time = settle + collection, hard-capped below TORCH_MAX_MS (CLAUDE.md §1.3).
                // Bright torch frames can also drop tracking, so carry-over stays on here too.
                carryOverAllowed = true
                torch = try {
                    collectOpen(Thresholds.BURST_OPEN_FRAMES, Thresholds.TORCH_MAX_MS - settle - 150)
                } finally { carryOverAllowed = false }
            } finally {
                withContext(NonCancellable) {
                    screenLight.value = ScreenLight.NORMAL
                    if (!front) cam.setTorch(false)
                }
            }
        } finally {
            withContext(NonCancellable) { cam.unlock() }
        }
        guide.done()
        val ccm = prefs.ccm
        val m = withContext(Dispatchers.Default) { ScleraMeasurer.measure(ambient, torch, ccm) }
        delay(400)
        return Pair(m, torch)
    }

    /**
     * Collect frames until [nOpen] of them have the eye open (openness ≥ MIN_OPENNESS), or the
     * time / frame cap is hit. Blinks are common right when the torch comes on.
     */
    private suspend fun collectOpen(nOpen: Int, timeoutMs: Long): List<EyeFrame> {
        val ch = Channel<EyeFrame>(Channel.UNLIMITED)
        sink = ch
        val out = ArrayList<EyeFrame>()
        var open = 0
        withTimeoutOrNull(timeoutMs) {
            while (open < nOpen && out.size < Thresholds.BURST_MAX_FRAMES) {
                val f = ch.receive()
                out += f
                if (f.eye.openness >= Thresholds.MIN_OPENNESS) open++
            }
        }
        sink = null
        ch.close()
        return out
    }

    private suspend fun saveRecord(d: Decision, l: SideMeasurement?, r: SideMeasurement?, q: QualityResult) {
        dao.insert(
            CheckRecord(
                timestamp = System.currentTimeMillis(),
                outcome = d.outcome.name,
                bLeft = l?.bStar?.takeIf { !it.isNaN() }, bRight = r?.bStar?.takeIf { !it.isNaN() },
                bMean = d.bStar, delta = d.delta,
                aLeft = l?.aStar?.takeIf { !it.isNaN() }, aRight = r?.aStar?.takeIf { !it.isNaN() },
                lLeft = l?.lStarRaw?.takeIf { !it.isNaN() }, lRight = r?.lStarRaw?.takeIf { !it.isNaN() },
                retakeReasons = q.reasons.joinToString(",") { it.name },
                isBaselineCapture = isBaseline,
                mode = mode.code,
            )
        )
    }

    private fun speakResult(d: Decision) {
        when (d.outcome) {
            Outcome.NO_CONCERN -> guide.say("Check complete. No concern found today. You can look at the screen now.")
            Outcome.REFER -> guide.say("Check complete. Signs of yellowing. Please look at the screen and contact your GP.")
            Outcome.RETAKE -> guide.say("We need to try again. " + (d.retakeReasons.firstOrNull() ?: RetakeReason.NOT_DETECTED).message)
        }
    }

    /** GP confirms the baseline (CLAUDE.md §2A.2). */
    fun confirmBaseline(bStar: Double, monitoring: Boolean, done: () -> Unit) {
        scope.launch {
            val b = baselineBuilt
            dao.setBaseline(com.scleracheck.data.Baseline(id = mode.code, timestamp = System.currentTimeMillis(), bStar = bStar,
                monitoring = monitoring, sd = b?.sd, captures = b?.chosen?.size ?: 1))
            done()
        }
    }

    fun cancel() {
        job?.cancel()
        guiding = false; sink = null; skipRequested = false
        readiness.value = Readiness()
        camera?.let { runCatching { it.setTorch(false); it.unlock() } }
    }

    fun dispose() {
        if (Shutter.listener === shutterListener) Shutter.listener = null
        cancel()
        scope.cancel()
        light.stop()
        guide.release()
        camera?.release()
        tracker?.close()
    }

    companion object { const val TAG = "ScleraCheck" }
}
