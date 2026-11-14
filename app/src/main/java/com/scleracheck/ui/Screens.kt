package com.scleracheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scleracheck.BuildConfig
import com.scleracheck.data.AppDb
import com.scleracheck.data.CaptureMode
import com.scleracheck.data.CheckRecord
import com.scleracheck.data.Prefs
import com.scleracheck.decision.Thresholds
import com.scleracheck.report.PdfExporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Green = Color(0xFF1E8C3C)
val Amber = Color(0xFFC77700)
val Red = Color(0xFFC62828)

@Composable
fun ScleraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF005EB8)), content = content)
}

/** CLAUDE.md §1.1: on first run and on every result. */
@Composable
fun NotDiagnosticNotice(modifier: Modifier = Modifier) {
    Text(
        "ScleraCheck is a screening aid, not a diagnostic device. It cannot tell you whether you have jaundice. " +
            "If you feel unwell, or are worried, contact your GP or call NHS 111 — whatever this app says.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.DarkGray,
        modifier = modifier,
    )
}

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Before you start", style = MaterialTheme.typography.headlineMedium)
        Text("This app looks at the colour of the white of your eye and compares it with the baseline your GP recorded. " +
            "It can tell you if there are signs of yellowing so you know to contact your GP.")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Text("This is NOT a diagnostic device. It does not diagnose jaundice or any other condition.",
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        }
        Text("During each check a light on the back of the phone turns on for about a second. It is not a flashing light.")
        Text("Images of your face are never stored. Only colour measurements are kept, on this phone.")
        NotDiagnosticNotice()
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("I understand") }
    }
}

@Composable
fun HomeScreen(onCheck: () -> Unit, onHistory: () -> Unit, onClinician: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val dao = remember { AppDb.get(ctx).dao() }
    var mode by remember { mutableStateOf(prefs.captureMode) }
    val baseline by remember(mode) { dao.baselineFlow(mode.code) }.collectAsState(initial = null)
    val last by dao.lastValid().collectAsState(initial = null)
    var dev by remember { mutableStateOf(prefs.devMode) }
    var label by remember { mutableStateOf(DevLabel.current) }
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ScleraCheck", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (baseline == null) {
                    Text("No baseline yet for ${if (mode == CaptureMode.FRONT_SCREEN) "the front camera" else "the back camera"}", fontWeight = FontWeight.Bold)
                    Text("Your GP records a baseline in clinician mode. You can still do a check; it will only use the safety threshold.")
                } else {
                    Text("Baseline recorded ${fmt.format(Date(baseline!!.timestamp))}")
                }
                last?.let {
                    Text("Last check: ${fmt.format(Date(it.timestamp))}")
                    val due = it.timestamp + prefs.checkFrequencyDays * 86_400_000L
                    Text(if (System.currentTimeMillis() >= due) "A check is due now." else "Next check due ${fmt.format(Date(due))}")
                }
            }
        }
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Start eye check", fontSize = 20.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureMode.entries.sortedByDescending { it.code }.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m; prefs.captureMode = m }, label = { Text(m.label) })
            }
        }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("My results") }
        OutlinedButton(onClick = onClinician, modifier = Modifier.fillMaxWidth()) { Text("Clinician mode") }

        Text("How to do a check", fontWeight = FontWeight.Bold)
        Text(if (mode == CaptureMode.FRONT_SCREEN)
            "1. Sit somewhere with normal room light, away from windows.\n" +
            "2. Hold the phone like a selfie and follow the voice: it will guide you to about a hand's width away.\n" +
            "3. Put one eye in the middle of the screen, then look left, then right, when asked.\n" +
            "4. The screen goes black, then flashes white for about a second. Wait for the beep after each side."
        else
            "1. Ask someone to hold the phone with the BACK camera pointing at your eye.\n" +
            "2. They follow the voice and the screen: it guides them to about a hand's width away.\n" +
            "3. Look left, then right, when asked.\n" +
            "4. A light turns on briefly. Wait for the beep after each side.")

        if (BuildConfig.DEV_TOOLS_AVAILABLE) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Developer mode (overlay, live numbers, save patches + CSV)", Modifier.weight(1f))
                Switch(checked = dev, onCheckedChange = { dev = it; prefs.devMode = it })
            }
            if (dev) OutlinedTextField(value = label, onValueChange = { label = it; DevLabel.current = it.replace(",", "_") },
                label = { Text("Experiment label (person_lighting)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        NotDiagnosticNotice()
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val checks by dao.all().collectAsState(initial = emptyList())
    val baseline by remember { dao.baselineFlow(Prefs(ctx).captureMode.code) }.collectAsState(initial = null)
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.UK) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Yellowness trend", style = MaterialTheme.typography.titleLarge)
        }
        // Only checks from the currently selected camera: the two cameras' numbers aren't comparable.
        val modeCode = remember { Prefs(ctx).captureMode.code }
        TrendChart(checks.filter { !it.isBaselineCapture && it.bMean != null && it.mode == modeCode }, baseline?.bStar,
            Modifier.fillMaxWidth().height(220.dp))
        Text("Blue line: baseline. Dashed: baseline + ${Thresholds.T_CHANGE} (change threshold). Red dots: Refer.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { PdfExporter.exportAndShare(ctx, checks, baseline) }, modifier = Modifier.fillMaxWidth()) {
            Text("Export PDF for GP")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(checks.reversed()) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(fmt.format(Date(r.timestamp)), Modifier.weight(1f))
                    Text(outcomeLabel(r) + (if (r.isBaselineCapture) " (baseline)" else ""), Modifier.weight(1.3f), color = outcomeColour(r.outcome))
                    Text(r.bMean?.let { "%.1f".format(it) } ?: "—", Modifier.weight(0.6f))
                }
                HorizontalDivider()
            }
        }
    }
}

private fun outcomeLabel(r: CheckRecord) = when (r.outcome) {
    "NO_CONCERN" -> "No concern"; "REFER" -> "Refer to GP"; else -> "Retake"
}

fun outcomeColour(o: String) = when (o) { "NO_CONCERN" -> Green; "REFER" -> Red; else -> Amber }

@Composable
fun TrendChart(points: List<CheckRecord>, baseline: Double?, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFFF5F7FA), RoundedCornerShape(8.dp))) {
        val vals = points.map { it.bMean!! } + listOfNotNull(baseline, baseline?.plus(Thresholds.T_CHANGE))
        if (vals.isEmpty()) return@Canvas
        val lo = vals.min() - 2; val hi = vals.max() + 2
        val pad = 16f
        fun y(v: Double) = (size.height - pad - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
        fun x(i: Int) = if (points.size <= 1) size.width / 2 else pad + i * (size.width - 2 * pad) / (points.size - 1)
        baseline?.let {
            drawLine(Color(0xFF005EB8), Offset(0f, y(it)), Offset(size.width, y(it)), 3f)
            drawLine(Amber, Offset(0f, y(it + Thresholds.T_CHANGE)), Offset(size.width, y(it + Thresholds.T_CHANGE)), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        }
        for (i in 1 until points.size) drawLine(Color.Gray, Offset(x(i - 1), y(points[i - 1].bMean!!)), Offset(x(i), y(points[i].bMean!!)), 2f)
        points.forEachIndexed { i, p -> drawCircle(if (p.outcome == "REFER") Red else Green, 8f, Offset(x(i), y(p.bMean!!))) }
    }
}

/** GP view. PIN-gated so patients don't overwrite their baseline. Demo PIN: 0000. */
@Composable
fun ClinicianScreen(onBack: () -> Unit, onRecordBaseline: () -> Unit, onHistory: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val dao = remember { AppDb.get(ctx).dao() }
    val baseline by remember { dao.baselineFlow(Prefs(ctx).captureMode.code) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(ClinicianSession.unlocked) }
    var pin by remember { mutableStateOf("") }
    var freq by remember { mutableIntStateOf(prefs.checkFrequencyDays) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Clinician mode", style = MaterialTheme.typography.titleLarge)
        }
        if (!unlocked) {
            OutlinedTextField(pin, { pin = it }, label = { Text("Clinician PIN (demo: 0000)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            Button(onClick = { if (pin == "0000") { unlocked = true; ClinicianSession.unlocked = true } }) { Text("Unlock") }
            return@Column
        }
        Text("1. Colour reference card", fontWeight = FontWeight.Bold)
        Text("Not in this build: the device uses an identity colour matrix with a fixed daylight white balance and " +
            "torch-only differencing. The 3×3 matrix fit is implemented and unit-tested (ColourCorrection.fit); the card-scanning screen is next.",
            style = MaterialTheme.typography.bodySmall)

        Text("2. Baseline (${prefs.captureMode.label})", fontWeight = FontWeight.Bold)
        Text(baseline?.let { "Current baseline b* = %.1f%s".format(it.bStar, if (it.monitoring) " (monitoring baseline)" else "") } ?: "No baseline recorded.")
        Button(onClick = onRecordBaseline, modifier = Modifier.fillMaxWidth()) { Text("Record baseline with patient") }

        Text("3. Check frequency", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Daily", 3 to "Every 3 days", 7 to "Weekly").forEach { (d, l) ->
                FilterChip(selected = freq == d, onClick = { freq = d; prefs.checkFrequencyDays = d }, label = { Text(l) })
            }
        }
        Text("Referral rule: refer if Δb* > ${Thresholds.T_CHANGE} on ${Thresholds.CONSECUTIVE_FOR_REFER} consecutive checks, or b* > ${Thresholds.T_ABSOLUTE}. " +
            "Thresholds are placeholders until the repeatability study (see Thresholds.kt).", style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Trend chart and PDF export") }
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all data", color = Red) }
        Spacer(Modifier.height(8.dp))
        NotDiagnosticNotice()
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all data?") },
        text = { Text("Deletes the baseline and every check on this phone.") },
        confirmButton = { TextButton(onClick = { scope.launch { dao.clear(); dao.clearBaseline() }; confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

object ClinicianSession { var unlocked = false }
