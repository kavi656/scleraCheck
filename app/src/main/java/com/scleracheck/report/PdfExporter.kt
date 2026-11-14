package com.scleracheck.report

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.scleracheck.data.Baseline
import com.scleracheck.data.CheckRecord
import com.scleracheck.decision.Thresholds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mocked "send to GP" (CLAUDE.md §1.4, §2C): a local PDF handed to the Android share sheet.
 * Nothing leaves the device unless the user picks a target in the share sheet.
 */
object PdfExporter {

    fun exportAndShare(ctx: Context, checks: List<CheckRecord>, baseline: Baseline?) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4 @72dpi
        val c = page.canvas
        val text = Paint().apply { textSize = 12f; color = Color.BLACK; isAntiAlias = true }
        val title = Paint(text).apply { textSize = 18f; isFakeBoldText = true }
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.UK)
        var y = 50f
        c.drawText("ScleraCheck — eye yellowness trend", 40f, y, title); y += 22
        c.drawText("Screening aid only. NOT a diagnostic device.", 40f, y, text); y += 18
        c.drawText("Generated ${fmt.format(Date())}", 40f, y, text); y += 24
        if (baseline != null) {
            c.drawText("Baseline b* = %.1f  (%s, %s)".format(baseline.bStar, fmt.format(Date(baseline.timestamp)),
                if (baseline.monitoring) "monitoring baseline: patient jaundiced at onboarding" else "GP confirmed not jaundiced"), 40f, y, text)
        } else c.drawText("No baseline recorded.", 40f, y, text)
        y += 18
        c.drawText("Rule: refer if Δb* > %.1f on %d consecutive checks, or b* > %.1f.".format(
            Thresholds.T_CHANGE, Thresholds.CONSECUTIVE_FOR_REFER, Thresholds.T_ABSOLUTE), 40f, y, text)
        y += 20

        // Chart
        val valid = checks.filter { it.bMean != null && !it.isBaselineCapture }
        val left = 60f; val top = y + 10; val w = 480f; val h = 220f
        val axis = Paint().apply { color = Color.DKGRAY; strokeWidth = 1f; style = Paint.Style.STROKE }
        c.drawRect(left, top, left + w, top + h, axis)
        val vals = valid.map { it.bMean!! } + listOfNotNull(baseline?.bStar, baseline?.bStar?.plus(Thresholds.T_CHANGE))
        if (vals.isNotEmpty()) {
            val lo = vals.min() - 2; val hi = vals.max() + 2
            fun yOf(v: Double) = (top + h - (v - lo) / (hi - lo) * h).toFloat()
            c.drawText("%.0f".format(hi), 30f, top + 10, text); c.drawText("%.0f".format(lo), 30f, top + h, text)
            baseline?.let {
                val bl = Paint().apply { color = Color.rgb(0, 110, 200); strokeWidth = 1.5f }
                c.drawLine(left, yOf(it.bStar), left + w, yOf(it.bStar), bl)
                val th = Paint().apply { color = Color.rgb(200, 120, 0); strokeWidth = 1f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f) }
                c.drawLine(left, yOf(it.bStar + Thresholds.T_CHANGE), left + w, yOf(it.bStar + Thresholds.T_CHANGE), th)
            }
            val pt = Paint().apply { isAntiAlias = true }
            valid.forEachIndexed { i, r ->
                val x = left + if (valid.size == 1) w / 2 else i * w / (valid.size - 1)
                pt.color = if (r.outcome == "REFER") Color.RED else Color.rgb(30, 140, 60)
                c.drawCircle(x, yOf(r.bMean!!), 4f, pt)
            }
        }
        y = top + h + 30
        c.drawText("Blue: baseline. Dashed: baseline + T_change. Red dots: Refer.", 40f, y, text); y += 24

        c.drawText("Recent checks", 40f, y, title.apply { textSize = 14f }); y += 18
        for (r in checks.takeLast(20).reversed()) {
            if (y > 800) break
            val b = r.bMean?.let { "%.1f".format(it) } ?: "—"
            val d = r.delta?.let { "%+.1f".format(it) } ?: ""
            c.drawText("${fmt.format(Date(r.timestamp))}   ${r.outcome}${if (r.isBaselineCapture) " (baseline)" else ""}   b*=$b $d", 40f, y, text)
            y += 16
        }
        doc.finishPage(page)
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "scleracheck_report.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Send report to GP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
