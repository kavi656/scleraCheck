import SwiftUI
import UIKit
import Charts
import ScleraCore

struct HomeView: View {
    let onCheck: () -> Void
    let onHistory: () -> Void
    let onClinician: () -> Void
    @EnvironmentObject var store: Store
    @State private var mode = Prefs.captureMode
    @State private var dev = Prefs.devMode
    @State private var label = Prefs.devLabel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("ScleraCheck").font(.largeTitle.bold()).foregroundStyle(nhsBlue)
                VStack(alignment: .leading, spacing: 4) {
                    if let b = store.baseline(mode) {
                        Text("Baseline recorded \(b.timestamp.formatted(date: .abbreviated, time: .shortened))")
                    } else {
                        Text("No baseline yet for the \(mode == .frontScreen ? "front" : "back") camera").bold()
                        Text("Your GP records a baseline in clinician mode. You can still do a check; it will only use the safety threshold.")
                    }
                    if let last = store.lastValid {
                        Text("Last check: \(last.timestamp.formatted(date: .abbreviated, time: .shortened))")
                        let due = last.timestamp.addingTimeInterval(Double(Prefs.checkFrequencyDays) * 86_400)
                        Text(Date() >= due ? "A check is due now." : "Next check due \(due.formatted(date: .abbreviated, time: .shortened))")
                    }
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

                Button(action: onCheck) { Text("Start eye check").font(.title2).frame(maxWidth: .infinity, minHeight: 50) }
                    .buttonStyle(.borderedProminent)
                Picker("Camera", selection: $mode) {
                    ForEach([CaptureMode.frontScreen, .rearTorch]) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .onChange(of: mode) { Prefs.captureMode = $0 }
                Button(action: onHistory) { Text("My results").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                Button(action: onClinician) { Text("Clinician mode").frame(maxWidth: .infinity) }.buttonStyle(.bordered)

                Text("How to do a check").bold()
                Text(mode == .frontScreen
                     ? "1. Sit somewhere with normal room light, away from windows.\n2. Hold the phone like a selfie and follow the voice: it will guide you to about a hand's width away.\n3. Put one eye in the middle of the screen, then look left, then right, when asked.\n4. The screen goes black, then flashes white for about a second. Wait for the beep after each side."
                     : "1. Ask someone to hold the phone with the BACK camera pointing at your eye.\n2. They follow the voice and the screen: it guides them to about a hand's width away.\n3. Look left, then right, when asked.\n4. A light turns on briefly. Wait for the beep after each side.")

                if Prefs.devToolsAvailable {
                    Divider()
                    Toggle("Developer mode (overlay, live numbers, patches + CSV in Files)", isOn: $dev)
                        .onChange(of: dev) { Prefs.devMode = $0 }
                    if dev {
                        TextField("Experiment label (person_lighting)", text: $label)
                            .textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never)
                            .onChange(of: label) { Prefs.devLabel = $0.replacingOccurrences(of: ",", with: "_") }
                    }
                }
                NotDiagnosticNotice()
            }.padding(24)
        }
    }
}

struct HistoryView: View {
    let onBack: () -> Void
    @EnvironmentObject var store: Store
    private let mode = Prefs.captureMode
    @State private var pdfURL: URL?

    var body: some View {
        let points = store.checks.filter { !$0.isBaselineCapture && $0.bMean != nil && $0.mode == mode }
        let baseline = store.baseline(mode)
        VStack(alignment: .leading, spacing: 12) {
            HStack { Button("← Back", action: onBack); Text("Yellowness trend").font(.title2.bold()) }
            Chart {
                if let b = baseline {
                    RuleMark(y: .value("Baseline", b.bStar)).foregroundStyle(nhsBlue)
                    RuleMark(y: .value("Change threshold", b.bStar + Thresholds.tChange))
                        .foregroundStyle(amber).lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                }
                ForEach(points) { r in
                    LineMark(x: .value("Date", r.timestamp), y: .value("b*", r.bMean!)).foregroundStyle(.gray)
                    PointMark(x: .value("Date", r.timestamp), y: .value("b*", r.bMean!))
                        .foregroundStyle(r.outcome == .refer ? alertRed : okGreen)
                }
            }
            .frame(height: 220)
            Text("Blue: baseline. Dashed: baseline + \(String(format: "%.1f", Thresholds.tChange)) (change threshold). Red: Refer. \(mode.label) checks only.")
                .font(.caption)
            if let url = pdfURL {
                ShareLink(item: url) { Label("Send PDF to GP", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
            } else {
                Button { pdfURL = PdfExporter.make(checks: store.checks.filter { $0.mode == mode }, baseline: baseline, mode: mode) } label: {
                    Text("Export PDF for GP").frame(maxWidth: .infinity)
                }.buttonStyle(.borderedProminent)
            }
            List(Array(store.checks.reversed())) { r in
                HStack {
                    Text(r.timestamp.formatted(date: .abbreviated, time: .shortened)).font(.caption)
                    Spacer()
                    Text(label(r) + (r.isBaselineCapture ? " (baseline)" : "")).foregroundStyle(colour(r.outcome))
                    Text(r.bMean.map { String(format: "%.1f", $0) } ?? "—").frame(width: 44, alignment: .trailing)
                }
            }.listStyle(.plain)
        }.padding(16)
    }

    private func label(_ r: CheckRecord) -> String {
        switch r.outcome { case .noConcern: return "No concern"; case .refer: return "Refer to GP"; case .retake: return "Retake" }
    }
    private func colour(_ o: Outcome) -> Color { o == .noConcern ? okGreen : (o == .refer ? alertRed : amber) }
}

/// GP view, PIN-gated so patients don't overwrite their baseline. Demo PIN: 0000.
struct ClinicianView: View {
    let onBack: () -> Void
    let onRecordBaseline: () -> Void
    let onHistory: () -> Void
    @EnvironmentObject var store: Store
    @State private var unlocked = ClinicianSession.unlocked
    @State private var pin = ""
    @State private var freq = Prefs.checkFrequencyDays
    @State private var confirmClear = false
    private let mode = Prefs.captureMode

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack { Button("← Back", action: onBack); Text("Clinician mode").font(.title2.bold()) }
                if !unlocked {
                    SecureField("Clinician PIN (demo: 0000)", text: $pin).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                    Button("Unlock") { if pin == "0000" { unlocked = true; ClinicianSession.unlocked = true } }.buttonStyle(.borderedProminent)
                } else {
                    Text("1. Colour reference card").bold()
                    Text("Not in this build: fixed daylight white balance + light-only differencing with an identity colour matrix. The 3×3 matrix fit is implemented and tested (ColourCorrection.fit).")
                        .font(.caption)
                    Text("2. Baseline (\(mode.label))").bold()
                    Text(store.baseline(mode).map { String(format: "Current baseline b* = %.1f%@", $0.bStar, $0.monitoring ? " (monitoring baseline)" : "") } ?? "No baseline recorded.")
                    Button(action: onRecordBaseline) { Text("Record baseline with patient").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                    Text("3. Check frequency").bold()
                    Picker("Frequency", selection: $freq) {
                        Text("Daily").tag(1); Text("Every 3 days").tag(3); Text("Weekly").tag(7)
                    }.pickerStyle(.segmented).onChange(of: freq) { Prefs.checkFrequencyDays = $0 }
                    Text("Referral rule: refer if Δb* > \(String(format: "%.1f", Thresholds.tChange)) on \(Thresholds.consecutiveForRefer) consecutive checks, or b* > \(String(format: "%.1f", Thresholds.tAbsolute)). Thresholds are placeholders until the repeatability study.")
                        .font(.caption)
                    Button(action: onHistory) { Text("Trend chart and PDF export").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    Button(role: .destructive) { confirmClear = true } label: { Text("Clear all data").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                }
                NotDiagnosticNotice()
            }.padding(24)
        }
        .alert("Clear all data?", isPresented: $confirmClear) {
            Button("Delete", role: .destructive) { store.clearAll() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Deletes the baselines and every check on this phone.") }
    }
}

enum ClinicianSession { static var unlocked = false }

/// Mocked "send to GP" (CLAUDE.md §1.4, §2C): a local PDF handed to the share sheet.
enum PdfExporter {
    static func make(checks: [CheckRecord], baseline: Baseline?, mode: CaptureMode) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("scleracheck_report.pdf")
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let r = UIGraphicsPDFRenderer(bounds: page)
        let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 11)]
        let title: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 18)]
        let df = DateFormatter(); df.dateFormat = "dd MMM yyyy HH:mm"
        do {
            try r.writePDF(to: url) { ctx in
                ctx.beginPage()
                var y: CGFloat = 40
                func line(_ s: String, _ a: [NSAttributedString.Key: Any]? = nil, gap: CGFloat = 16) {
                    (s as NSString).draw(at: CGPoint(x: 40, y: y), withAttributes: a ?? body); y += gap
                }
                line("ScleraCheck — eye yellowness trend", title, gap: 26)
                line("Screening aid only. NOT a diagnostic device.")
                line("Generated \(df.string(from: Date()))   Camera: \(mode.label)", gap: 22)
                if let b = baseline {
                    line(String(format: "Baseline b* = %.1f (%@, %@)", b.bStar, df.string(from: b.timestamp),
                                b.monitoring ? "monitoring baseline: patient jaundiced at onboarding" : "GP confirmed not jaundiced"))
                } else { line("No baseline recorded.") }
                line(String(format: "Rule: refer if Δb* > %.1f on %d consecutive checks, or b* > %.1f.", Thresholds.tChange, Thresholds.consecutiveForRefer, Thresholds.tAbsolute), gap: 20)

                // Chart
                let valid = checks.filter { $0.bMean != nil && !$0.isBaselineCapture }
                let box = CGRect(x: 60, y: y + 10, width: 480, height: 220)
                let g = ctx.cgContext
                g.setStrokeColor(UIColor.darkGray.cgColor); g.stroke(box)
                let vals = valid.compactMap(\.bMean) + [baseline?.bStar, baseline.map { $0.bStar + Thresholds.tChange }].compactMap { $0 }
                if let lo0 = vals.min(), let hi0 = vals.max() {
                    let lo = lo0 - 2, hi = hi0 + 2
                    func yOf(_ v: Double) -> CGFloat { box.maxY - CGFloat((v - lo) / (hi - lo)) * box.height }
                    if let b = baseline {
                        g.setStrokeColor(UIColor.systemBlue.cgColor); g.setLineWidth(1.5)
                        g.move(to: CGPoint(x: box.minX, y: yOf(b.bStar))); g.addLine(to: CGPoint(x: box.maxX, y: yOf(b.bStar))); g.strokePath()
                        g.setStrokeColor(UIColor.orange.cgColor); g.setLineDash(phase: 0, lengths: [6, 4])
                        g.move(to: CGPoint(x: box.minX, y: yOf(b.bStar + Thresholds.tChange))); g.addLine(to: CGPoint(x: box.maxX, y: yOf(b.bStar + Thresholds.tChange))); g.strokePath()
                        g.setLineDash(phase: 0, lengths: [])
                    }
                    for (i, rec) in valid.enumerated() {
                        let x = box.minX + (valid.count == 1 ? box.width / 2 : CGFloat(i) * box.width / CGFloat(valid.count - 1))
                        g.setFillColor((rec.outcome == .refer ? UIColor.systemRed : UIColor.systemGreen).cgColor)
                        g.fillEllipse(in: CGRect(x: x - 4, y: yOf(rec.bMean!) - 4, width: 8, height: 8))
                    }
                }
                y = box.maxY + 20
                line("Blue: baseline. Dashed: baseline + T_change. Red dots: Refer.", gap: 24)
                line("Recent checks", [.font: UIFont.boldSystemFont(ofSize: 13)], gap: 18)
                for rec in checks.suffix(20).reversed() where y < 800 {
                    line("\(df.string(from: rec.timestamp))   \(rec.outcome.rawValue)\(rec.isBaselineCapture ? " (baseline)" : "")   b*=\(rec.bMean.map { String(format: "%.1f", $0) } ?? "—") \(rec.delta.map { String(format: "%+.1f", $0) } ?? "")")
                }
            }
            return url
        } catch { return nil }
    }
}
