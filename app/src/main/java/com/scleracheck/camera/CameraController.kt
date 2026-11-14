package com.scleracheck.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.scleracheck.decision.Thresholds
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rear camera + torch (CLAUDE.md §0). CameraX for the pipeline, Camera2Interop for manual control.
 *
 * Capability policy (CLAUDE.md §9): every manual control is checked at runtime and we fall back:
 *  - White balance: fixed DAYLIGHT preset if offered (same gains every session, so the torch-only
 *    colour is comparable day to day); else AWB lock at capture time.
 *  - Exposure: MANUAL_SENSOR ⇒ fixed ISO + exposure copied from the last auto-exposed frame;
 *    else AE_LOCK. Either way exposure is identical for the torch-off and torch-on bursts,
 *    which is what flash/no-flash differencing requires.
 *  - Torch strength: FLASH_STRENGTH_LEVEL on Android 15+ when the device reports >1 level; else default.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context, val front: Boolean) {

    data class Caps(
        val manualSensor: Boolean,
        val daylightAwb: Boolean,
        val hasFlash: Boolean,
        val torchMaxLevel: Int,
        val focalPx: Double?,
        /** Closest distance the lens can focus, mm (from LENS_INFO_MINIMUM_FOCUS_DISTANCE). null = fixed focus / unknown. */
        val minFocusMm: Double?,
        /** Max focus in diopters (= 1000 / minFocusMm). */
        val maxDiopters: Float,
        /** True if we can drive the lens ourselves: MANUAL_SENSOR + focus distances not UNCALIBRATED. */
        val manualFocus: Boolean,
        val minZoom: Float,
        val maxZoom: Float,
        val describe: String,
    )

    var caps: Caps? = null; private set
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    @Volatile var analysisW = 960; private set
    @Volatile var analysisH = 1280; private set
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var lastExposureNs: Long? = null
    @Volatile private var lastIso: Int? = null
    @Volatile private var lastFrameDurationNs: Long? = null

    private var baseOptions: Map<CaptureRequest.Key<*>, Any> = emptyMap()
    private var lockOptions: Map<CaptureRequest.Key<*>, Any> = emptyMap()
    private var torchOptions: Map<CaptureRequest.Key<*>, Any> = emptyMap()
    private var focusOptions: Map<CaptureRequest.Key<*>, Any> = emptyMap()

    /** Last autofocus state (CaptureResult.CONTROL_AF_STATE), for the developer overlay. */
    @Volatile var afState: Int? = null; private set
    @Volatile var lensDiopters: Float? = null; private set

    /** Zoom ratio actually applied to the last frame (CaptureResult.CONTROL_ZOOM_RATIO, API 30+). */
    @Volatile var currentZoom: Float = 1f; private set
    /** Physical lens the logical camera is streaming from (multi-camera phones switch lenses with zoom). */
    @Volatile var activePhysicalId: String? = null; private set
    @Volatile private var commandedZoom: Float = 1f

    /** Upright frame callback (runs on the analysis thread). */
    var onFrame: ((Bitmap, Long) -> Unit)? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            afState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            lensDiopters = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
            if (Build.VERSION.SDK_INT >= 30) {
                result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)?.let { currentZoom = it }
            } else currentZoom = commandedZoom
            activePhysicalId = result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            // Only remember auto-exposed values (before we lock) so manual exposure copies the AE choice.
            if (lockOptions.isEmpty()) {
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
                result.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION)?.let { lastFrameDurationNs = it }
            }
        }
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView, onReady: (Caps) -> Unit) {
        this.previewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val previewBuilder = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            )
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            val preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> handle(proxy) }

            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            camera = cam
            val c = readCaps(cam, analysis.resolutionInfo?.resolution)
            caps = c
            baseOptions = if (c.daylightAwb) mapOf(CaptureRequest.CONTROL_AWB_MODE to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT) else emptyMap()
            applyOptions()
            Log.i(TAG, "Camera caps: ${c.describe}")
            onReady(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun handle(proxy: ImageProxy) {
        try {
            val cb = onFrame ?: return
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, false)
            analysisW = upright.width; analysisH = upright.height
            cb(upright, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed", t)
        } finally {
            proxy.close()
        }
    }

    @SuppressLint("RestrictedApi")
    private fun readCaps(cam: Camera, analysisSize: Size?): Caps {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val capsArr = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = capsArr.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val awbModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val daylight = awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
        val flash = info.getCameraCharacteristic(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val torchMax = if (Build.VERSION.SDK_INT >= 35) info.getCameraCharacteristic(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1

        // Focal length in pixels of the (upright) analysis image, for iris-based distance.
        // Prefer intrinsic calibration (fx in pixel-array units); else focal_mm × px/mm.
        val pixelArray = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val longSidePx = analysisSize?.let { maxOf(it.width, it.height) }?.toDouble()
        var focalPx: Double? = null
        val intr = info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (intr != null && intr[0] > 0f && pixelArray != null && longSidePx != null) {
            focalPx = intr[0] * longSidePx / maxOf(pixelArray.width, pixelArray.height)
        } else {
            val fl = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (fl != null && phys != null && longSidePx != null) focalPx = fl * longSidePx / maxOf(phys.width, phys.height)
        }
        // Focus range. LENS_INFO_MINIMUM_FOCUS_DISTANCE is in diopters (1/m); 0 means fixed focus.
        val maxDiop = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val minFocusMm = if (maxDiop > 0f) 1000.0 / maxDiop else null
        // Focus distances are only meaningful in real units if not UNCALIBRATED (Camera2 docs,
        // LENS_INFO_FOCUS_DISTANCE_CALIBRATION). Many budget HALs report UNCALIBRATED; then we use AF triggers.
        val calib = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
            ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        val manualFocus = manual && maxDiop > 0f && calib != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        val calibName = when (calib) { 2 -> "calibrated"; 1 -> "approx"; else -> "uncalibrated" }
        val d = "${Build.MANUFACTURER} ${Build.MODEL}\nmanualSensor=$manual daylightAWB=$daylight flash=$flash torchLevels=$torchMax\n" +
            "focalPx=${focalPx?.toInt()} analysis=$analysisSize\nminFocus=${minFocusMm?.toInt() ?: "fixed"}mm focusCal=$calibName " +
            "focusMode=${if (manualFocus) "MANUAL(from iris distance)" else "AF(eye-targeted)"}"
        val zs = cam.cameraInfo.zoomState.value
        val minZ = zs?.minZoomRatio ?: 1f; val maxZ = zs?.maxZoomRatio ?: 1f
        return Caps(manual, daylight, flash, torchMax, focalPx, minFocusMm, maxDiop, manualFocus, minZ, maxZ,
            d + "\nzoom range %.1f–%.1f×".format(minZ, maxZ))
    }

    @Synchronized
    private fun applyOptions() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        @Suppress("UNCHECKED_CAST")
        fun put(m: Map<CaptureRequest.Key<*>, Any>) = m.forEach { (k, v) -> b.setCaptureRequestOption(k as CaptureRequest.Key<Any>, v) }
        put(baseOptions); put(focusOptions); put(lockOptions); put(torchOptions)
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    /**
     * Manual focus from the iris-based distance estimate (only when caps.manualFocus).
     * Close-up autofocus often hunts or locks onto the eyelashes/face; since we already know how far
     * away the eye is (iris diameter ≈ 11.7 mm), we can put the lens there directly.
     * LENS_FOCUS_DISTANCE is in diopters = 1000 / distance_mm.
     */
    fun setFocusDistanceMm(mm: Double) {
        val c = caps ?: return
        if (!c.manualFocus) return
        val diop = (1000.0 / mm).toFloat().coerceIn(0f, c.maxDiopters)
        focusOptions = mapOf(
            CaptureRequest.CONTROL_AF_MODE to CameraMetadata.CONTROL_AF_MODE_OFF,
            CaptureRequest.LENS_FOCUS_DISTANCE to diop,
        )
        applyOptions()
    }

    /**
     * Zoom (CameraX setZoomRatio → CONTROL_ZOOM_RATIO). The ratio is defined relative to the
     * logical camera's 1× field of view, so the effective focal length in the output image is
     * focal_1x × zoomRatio, whichever physical lens the phone streams from (Camera2 docs, CONTROL_ZOOM_RATIO).
     */
    fun setZoom(ratio: Float) {
        val c = caps ?: return
        val r = ratio.coerceIn(maxOf(1f, c.minZoom), c.maxZoom)
        if (abs(r - commandedZoom) < 0.01f) return
        commandedZoom = r
        camera?.cameraControl?.setZoomRatio(r)
    }

    /** AF fallback: run a focus scan metered on the eye (normalised upright image coords). */
    fun focusOnEye(nx: Float, ny: Float) {
        val cam = camera ?: return
        val pv = previewView ?: return
        if (caps?.manualFocus == true) return
        ContextCompat.getMainExecutor(context).execute {
            if (pv.width == 0) return@execute
            val (vx, vy) = ViewMapping.imageToView(nx, ny, analysisW, analysisH, pv.width, pv.height, front)
            val pt = pv.meteringPointFactory.createPoint(vx, vy, 0.08f) // small spot: the eye, not the face
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
            )
        }
    }

    /**
     * Focus on the eye, then lock exposure and white balance.
     * @param nx eye x normalised to the upright analysis image. @param ny likewise for y.
     */
    suspend fun focusAndLock(nx: Float, ny: Float, extraEv: Double = 0.0) {
        val cam = camera ?: return
        val c = caps ?: return
        val pv = previewView ?: return
        // PreviewView's factory maps view coords → sensor coords, handling rotation and FILL_CENTER crop.
        val (vx, vy) = ViewMapping.imageToView(nx, ny, analysisW, analysisH, pv.width, pv.height, front)
        val point = pv.meteringPointFactory.createPoint(vx, vy, 0.08f)
        // With manual focus the lens is already at the eye; only meter exposure.
        val flags = if (c.manualFocus) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val action = FocusMeteringAction.Builder(point, flags)
            .disableAutoCancel() // keep AF locked after the scan
            .build()
        withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                val f = cam.cameraControl.startFocusAndMetering(action)
                f.addListener({ if (cont.isActive) cont.resume(Unit) }, ContextCompat.getMainExecutor(context))
            }
        }
        // Leave headroom for the torch: under-expose by TORCH_EXPOSURE_EV before locking (see Thresholds).
        // Done via AE compensation so it works on every phone; the manual-sensor path below then copies
        // the compensated exposure, because captureCallback keeps recording until the lock is applied.
        appliedEvForLog = applyExposureCompensation((if (front) Thresholds.SCREEN_EXPOSURE_EV else Thresholds.TORCH_EXPOSURE_EV) + extraEv)
        kotlinx.coroutines.delay(450) // let AE converge to the new target (~10 frames)
        val exp = lastExposureNs; val iso = lastIso
        lockOptions = if (c.manualSensor && exp != null && iso != null) {
            buildMap {
                put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                put(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                put(CaptureRequest.SENSOR_SENSITIVITY, iso)
                lastFrameDurationNs?.let { put(CaptureRequest.SENSOR_FRAME_DURATION, it) }
                if (!c.daylightAwb) put(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        } else {
            buildMap {
                put(CaptureRequest.CONTROL_AE_LOCK, true)
                if (!c.daylightAwb) put(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        }
        applyOptions()
    }

    fun setTorch(on: Boolean) {
        val c = caps
        torchOptions = if (on && c?.hasFlash == true) buildMap {
            put(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            if (Build.VERSION.SDK_INT >= 35 && c.torchMaxLevel > 1) {
                val level = (c.torchMaxLevel * Thresholds.TORCH_STRENGTH_FRACTION).toInt().coerceIn(1, c.torchMaxLevel)
                put(CaptureRequest.FLASH_STRENGTH_LEVEL, level)
            }
        } else emptyMap()
        applyOptions()
    }

    /** Set AE compensation (in EV), clamped to what the device supports. Returns the EV applied. */
    fun applyExposureCompensation(ev: Double): Double {
        val cam = camera ?: return 0.0
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return 0.0
        val step = st.exposureCompensationStep.toDouble()
        if (step <= 0) return 0.0
        val idx = Math.round(ev / step).toInt().coerceIn(st.exposureCompensationRange.lower, st.exposureCompensationRange.upper)
        cam.cameraControl.setExposureCompensationIndex(idx)
        appliedEv = idx * step
        return appliedEv
    }

    /** EV compensation currently applied (for logging). */
    @Volatile var appliedEv = 0.0; private set
    /** EV used for the most recent capture (survives unlock(), for the CSV). */
    @Volatile var appliedEvForLog = 0.0; private set

    fun unlock() {
        applyExposureCompensation(0.0)
        torchOptions = emptyMap()
        focusOptions = emptyMap()
        lockOptions = emptyMap()
        applyOptions()
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    fun release() {
        onFrame = null
        torchOptions = emptyMap(); lockOptions = emptyMap(); focusOptions = emptyMap()
        runCatching { applyOptions() }
        runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        camera = null
        analysisExecutor.shutdown()
        runCatching { analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) }
    }

    companion object { const val TAG = "ScleraCamera" }
}

/** Maps upright analysis-image coordinates to PreviewView coordinates (PreviewView default FILL_CENTER). */
object ViewMapping {
    /** nx, ny normalised [0,1] in the image. Returns view px. */
    /** @param mirrored true for the front camera: PreviewView shows a mirror image, analysis frames are not mirrored. */
    fun imageToView(nx: Float, ny: Float, imgW: Int, imgH: Int, viewW: Int, viewH: Int, mirrored: Boolean = false): Pair<Float, Float> {
        @Suppress("NAME_SHADOWING") val nx = if (mirrored) 1f - nx else nx
        val s = maxOf(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
        val ox = (viewW - imgW * s) / 2f
        val oy = (viewH - imgH * s) / 2f
        return Pair(nx * imgW * s + ox, ny * imgH * s + oy)
    }
}
