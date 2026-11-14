package com.scleracheck.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.scleracheck.camera.CameraController
import com.scleracheck.decision.Outcome
import com.scleracheck.decision.Thresholds
import com.scleracheck.measure.SideMeasurement
import com.scleracheck.segmentation.PolygonUtil

@Composable
fun CheckScreen(baseline: Boolean, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ScleraCheck needs the camera to look at your eye. Nothing is uploaded.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            OutlinedButton(onClick = onExit) { Text("Back") }
        }
        return
    }
    // A fresh session per visit to this screen; released (camera, TTS, sensors) when it leaves.
    val app = ctx.applicationContext as android.app.Application
    val vm = remember { CheckViewModel(app).also { it.configure(baseline) } }
    DisposableEffect(vm) { onDispose { vm.dispose() } }
    val phase by vm.phase.collectAsState()

    when (val p = phase) {
        is CheckPhase.Finished -> ResultScreen(p, vm, onExit)
        else -> CapturePane(vm, p, onExit)
    }
}

@Composable
private fun CapturePane(vm: CheckViewModel, phase: CheckPhase, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val live by vm.live.collectAsState()
    val controller = remember { CameraController(ctx, front = vm.front) }
    var caps by remember { mutableStateOf<CameraController.Caps?>(null) }
    val light by vm.screenLight.collectAsState()
    val ready by vm.readiness.collectAsState()
    DisposableEffect(Unit) { onDispose { vm.cancel(); controller.release(); setBrightness(ctx, null) } }
    // Front mode: the screen is the light source. Full brightness only while it is white.
    LaunchedEffect(light) { setBrightness(ctx, if (light == CheckViewModel.ScreenLight.WHITE) 1f else null) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(factory = { c ->
                PreviewView(c).also { pv ->
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller.bind(owner, pv) { cps -> caps = cps; vm.attachCamera(controller, cps) }
                }
            }, modifier = Modifier.fillMaxSize())
            if (vm.devMode) EyeOverlay(live, vm.front, Modifier.fillMaxSize())
            if (vm.devMode) Text(devText(live, caps, vm, controller), color = Color.Yellow, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000)).padding(6.dp))
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            when (phase) {
                CheckPhase.Loading -> CircularProgressIndicator()
                is CheckPhase.Intro -> {
                    Text(if (vm.front) "Hold the phone like a selfie. The screen will go black, then flash white for about a second."
                        else "A light on the back of the phone will turn on for about a second during the check.",
                        color = Color.White, textAlign = TextAlign.Center)
                    phase.lightAdvice?.let { Text(it, color = Color(0xFFFFCC80), textAlign = TextAlign.Center) }
                    Button(onClick = { vm.start() }, enabled = caps != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(if (vm.isBaseline) "Start baseline capture" else "Start", fontSize = 18.sp)
                    }
                }
                is CheckPhase.Guiding -> {
                    Text(if (phase.step == 0) "Side 1 of 2" else "Side 2 of 2", color = Color.LightGray)
                    Text(phase.text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    ReadinessChecklist(ready)
                    Button(onClick = { vm.shutter() }, enabled = ready.stage != Readiness.Stage.IDLE && ready.eyeFound,
                        modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Capture now  (or press a volume button)", fontSize = 16.sp)
                    }
                }
                is CheckPhase.Capturing -> Text("Capturing… hold still", color = Color.White, fontSize = 26.sp)
                CheckPhase.Processing -> { CircularProgressIndicator(); Text("Measuring…", color = Color.White) }
                is CheckPhase.Finished -> {}
            }
            OutlinedButton(onClick = { vm.cancel(); onExit() }) { Text("Cancel", color = Color.White) }
        }
    }
    when (light) {
        CheckViewModel.ScreenLight.WHITE -> Box(Modifier.fillMaxSize().background(Color.White))
        CheckViewModel.ScreenLight.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
        CheckViewModel.ScreenLight.NORMAL -> {}
    }
    }
}

/**
 * Live checklist of what auto-capture is waiting for. ✓ = OK, ✗ = not yet (with the number where it helps).
 */
@Composable
private fun ReadinessChecklist(r: Readiness) {
    if (r.stage == Readiness.Stage.IDLE) return
    @Composable fun item(ok: Boolean, label: String) {
        Text((if (ok) "✓ " else "✗ ") + label, color = if (ok) Color(0xFF81C784) else Color(0xFFFFB74D), fontSize = 15.sp)
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(r.eyeFound, "Eye found")
        if (r.stage == Readiness.Stage.POSITION) {
            val d = r.distanceMm
            val distLabel = when {
                d == null -> "Distance"
                r.distOk -> "Distance %.0f cm".format(d / 10)
                d > r.distMaxMm -> "Distance %.0f cm → closer (target %.0f–%.0f)".format(d / 10, r.distMinMm / 10, r.distMaxMm / 10)
                else -> "Distance %.0f cm → further (target %.0f–%.0f)".format(d / 10, r.distMinMm / 10, r.distMaxMm / 10)
            }
            item(r.distOk, distLabel)
            item(r.centred, "Eye in the middle")
            item(r.still, "Holding still")
            item(r.zoomOk, "Zoomed in")
            if (r.hold > 0f) androidx.compose.material3.LinearProgressIndicator(progress = { r.hold }, modifier = Modifier.fillMaxWidth())
        } else {
            item(r.gazeOk, "Looking ${r.gazeDir}")
            item(r.still, "Holding still")
        }
    }
}

/** null = follow the system brightness. */
private fun setBrightness(ctx: android.content.Context, value: Float?) {
    val act = ctx as? android.app.Activity ?: return
    val lp = act.window.attributes
    lp.screenBrightness = value ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    act.window.attributes = lp
}

private fun devText(l: LiveInfo, caps: CameraController.Caps?, vm: CheckViewModel, cam: CameraController): String = buildString {
    append("dist: ${l.distanceMm?.let { "%.0f mm".format(it) } ?: "—"} (target ${vm.distMinMm.toInt()}–${vm.distMaxMm.toInt()})\n")
    val af = when (cam.afState) { 0 -> "inactive"; 1 -> "scanning"; 2 -> "focused(passive)"; 3 -> "scanning(passive)"
        4 -> "LOCKED"; 5 -> "FAILED"; 6 -> "unfocused(passive)"; null -> "—"; else -> "${cam.afState}" }
    append("zoom: %.2f×  lens id: ${cam.activePhysicalId ?: "—"}\n".format(cam.currentZoom))
    append("AF: $af  lens: ${cam.lensDiopters?.let { if (it > 0) "%.0f mm".format(1000 / it) else "inf" } ?: "—"}\n")
    append("sharp: ${l.sharpness?.let { "%.0f".format(it) } ?: "—"}  gaze: ${l.gaze?.let { "%+.2f".format(it) } ?: "—"}\n")
    append("live b* (uncorrected): ${l.liveB?.let { "%.1f".format(it) } ?: "—"}\n")
    append("open: ${l.eye?.openness?.let { "%.2f".format(it) } ?: "—"}\n")
    caps?.let { append(it.describe) }
}

/** Developer overlay: eyelid contour, shrunk segmentation polygon, dilated iris disk (build step 4). */
@Composable
private fun EyeOverlay(l: LiveInfo, mirrored: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val eye = l.eye ?: return@Canvas
        if (l.imgW == 0) return@Canvas
        val s = maxOf(size.width / l.imgW, size.height / l.imgH)
        val ox = (size.width - l.imgW * s) / 2f; val oy = (size.height - l.imgH * s) / 2f
        // Front camera: the preview is mirrored but analysis frames aren't.
        fun o(x: Float, y: Float) = Offset((if (mirrored) l.imgW - x else x) * s + ox, y * s + oy)
        fun poly(pts: List<com.scleracheck.segmentation.Pt>, c: Color) {
            val path = Path()
            pts.forEachIndexed { i, p -> val q = o(p.x, p.y); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            path.close()
            drawPath(path, c, style = Stroke(3f))
        }
        poly(eye.contour, Color.Cyan)
        poly(PolygonUtil.shrink(eye.contour, eye.centroid(), Thresholds.LID_SHRINK_X, Thresholds.LID_SHRINK_Y), Color.Green)
        drawCircle(Color.Magenta, eye.irisRadius * Thresholds.IRIS_DILATE * s, o(eye.irisCenter.x, eye.irisCenter.y), style = Stroke(3f))
    }
}

@Composable
private fun ResultScreen(p: CheckPhase.Finished, vm: CheckViewModel, onExit: () -> Unit) {
    val d = p.decision
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (p.isBaseline && d.outcome != Outcome.RETAKE && d.bStar != null) {
            BaselineConfirm(d.bStar, vm, onExit)
            Text(d.explanation, style = MaterialTheme.typography.bodyMedium)
            if (vm.devMode) DevPatches(p)
        } else {
            val (title, colour, steps) = when (d.outcome) {
                Outcome.NO_CONCERN -> Triple("No concern", Green,
                    "No sign of increased yellowing compared with your baseline. Do your next check as planned. " +
                        "If you notice yellow skin or eyes, dark urine, pale stools or itching, contact your GP anyway.")
                Outcome.REFER -> Triple("Refer to GP", Red,
                    "Signs of yellowing compared with your baseline. Please contact your GP surgery today. " +
                        "This is not a diagnosis. If you feel very unwell, call NHS 111.")
                Outcome.RETAKE -> Triple("Retake needed", Amber,
                    d.retakeReasons.joinToString("\n") { "• " + it.message })
            }
            Card(colors = CardDefaults.cardColors(containerColor = colour)) {
                Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp))
            }
            Text(steps, fontSize = 18.sp)
            if (d.outcome != Outcome.RETAKE) Text(d.explanation, style = MaterialTheme.typography.bodyMedium)
            if (vm.devMode) DevPatches(p)
            if (vm.devMode) Text(devResult(p), fontSize = 12.sp, color = Color.DarkGray)
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
        NotDiagnosticNotice()
    }
}

/** Developer mode: the exact sclera pixels that were measured, per side, so capture quality can be judged by eye. */
@Composable
private fun DevPatches(p: CheckPhase.Finished) {
    Text("What was measured (white of the eye only; black = excluded)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (side in 0..1) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val bmp = p.patches.getOrNull(side)
                if (bmp != null) {
                    androidx.compose.foundation.Image(bmp.asImageBitmap(), contentDescription = "side ${side + 1} patch",
                        modifier = Modifier.fillMaxWidth().background(Color.Black),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                } else {
                    Box(Modifier.fillMaxWidth().height(80.dp).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                        Text("no usable frame", color = Color.White, fontSize = 12.sp)
                    }
                }
                val m = if (side == 0) p.first else p.second
                Text("Side ${side + 1}: try ${p.attempts.getOrNull(side) ?: "?"}, ${m?.cleanPixels ?: 0} px" +
                    (m?.let { ", ${it.torchOpenFrames}/${it.torchFrames} open" } ?: ""), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BaselineConfirm(b: Double, vm: CheckViewModel, onExit: () -> Unit) {
    Text("Baseline captured", style = MaterialTheme.typography.headlineMedium)
    Text("Baseline yellowness index b* = %.1f (median of the captures below)".format(b), fontSize = 18.sp)
    Text("Clinician: confirm the patient's current status.")
    Button(onClick = { vm.confirmBaseline(b, monitoring = false, done = onExit) }, modifier = Modifier.fillMaxWidth()) {
        Text("Not currently jaundiced — save as baseline")
    }
    OutlinedButton(onClick = { vm.confirmBaseline(b, monitoring = true, done = onExit) }, modifier = Modifier.fillMaxWidth()) {
        Text("Currently jaundiced — save as monitoring baseline")
    }
    OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Discard") }
}

private fun devResult(p: CheckPhase.Finished): String {
    fun s(m: SideMeasurement?) = m?.let {
        "b*=%.2f a*=%.2f L*raw=%.1f naive b*=%s px=%d glare=%.2f vessel=%.2f torch/amb=%.2f sharp=%.0f gaze=%+.2f".format(
            it.bStar, it.aStar, it.lStarRaw, it.naiveBStar?.let { n -> "%.2f".format(n) } ?: "—", it.cleanPixels,
            it.glareFraction, it.vesselFraction, it.torchToAmbient, it.sharpness, it.gazeOffset)
    } ?: "—"
    return "Side 1: ${s(p.first)}\nSide 2: ${s(p.second)}\nΔ=${p.decision.delta?.let { "%.2f".format(it) } ?: "—"}"
}
