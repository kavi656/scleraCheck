package com.scleracheck.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.scleracheck.segmentation.EyeGeometry
import com.scleracheck.segmentation.Pt
import java.io.Closeable
import kotlin.math.hypot

/**
 * MediaPipe Face Landmarker (478 points = 468 face mesh + 10 iris points).
 * Landmark indices follow the canonical MediaPipe face mesh topology
 * (mediapipe/modules/face_geometry/data/canonical_face_model_uv_visualization.png and
 * the FACEMESH_LEFT_EYE / FACEMESH_RIGHT_EYE / FACEMESH_*_IRIS connection sets).
 *
 * "Right"/"left" here are the SUBJECT's anatomical eyes.
 */
class FaceLandmarkTracker(context: Context) : Closeable {

    private val landmarker: FaceLandmarker
    private var lastTs = 0L

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            // Low thresholds: at 10–15 cm only part of the face is in frame, so confidence drops.
            .setMinFaceDetectionConfidence(0.3f)
            .setMinFacePresenceConfidence(0.3f)
            .setMinTrackingConfidence(0.3f)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, opts)
    }

    /** Returns eyes [right, left] in pixel coords of [bitmap], or null if no face. */
    @Synchronized
    fun detect(bitmap: Bitmap, timestampMs: Long): List<EyeGeometry>? {
        val ts = if (timestampMs <= lastTs) lastTs + 1 else timestampMs // VIDEO mode needs strictly increasing ts
        lastTs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val lm = result.faceLandmarks().firstOrNull() ?: return null
        if (lm.size < 478) return null
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        fun p(i: Int): Pt { val l: NormalizedLandmark = lm[i]; return Pt(l.x() * w, l.y() * h) }
        fun eye(contour: IntArray, outer: Int, inner: Int, upper: Int, lower: Int, irisC: Int, ring: IntRange): EyeGeometry {
            val c = p(irisC)
            val r = ring.map { val q = p(it); hypot(q.x - c.x, q.y - c.y) }.average().toFloat()
            return EyeGeometry(contour.map { p(it) }, c, r, p(inner), p(outer), p(upper), p(lower))
        }
        return listOf(
            eye(RIGHT_EYE_CONTOUR, 33, 133, 159, 145, 468, 469..472),
            eye(LEFT_EYE_CONTOUR, 263, 362, 386, 374, 473, 474..477),
        )
    }

    override fun close() = landmarker.close()

    companion object {
        /** Ordered eyelid contours: upper lid outer→inner, then lower lid inner→outer. */
        val RIGHT_EYE_CONTOUR = intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 155, 154, 153, 145, 144, 163, 7)
        val LEFT_EYE_CONTOUR = intArrayOf(263, 466, 388, 387, 386, 385, 384, 398, 362, 382, 381, 380, 374, 373, 390, 249)
    }
}
