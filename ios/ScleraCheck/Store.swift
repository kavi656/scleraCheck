import Foundation
import ScleraCore

/// Which camera + light the check uses. Numbers from the two modes are NOT comparable, so baselines
/// and decisions are kept per mode (same as the Android app).
enum CaptureMode: Int, Codable, CaseIterable, Identifiable {
    case rearTorch = 0
    case frontScreen = 1
    var id: Int { rawValue }
    var label: String { self == .frontScreen ? "Front camera" : "Back + helper" }
}

struct CheckRecord: Codable, Identifiable {
    var id = UUID()
    var timestamp: Date
    var outcome: Outcome
    var bLeft: Double?
    var bRight: Double?
    var bMean: Double?
    var delta: Double?
    var retakeReasons: [RetakeReason]
    var isBaselineCapture: Bool
    var mode: CaptureMode
}

struct Baseline: Codable {
    var timestamp: Date
    var bStar: Double
    /// True if the GP said the patient IS currently jaundiced (monitoring baseline, CLAUDE.md §2A.2).
    var monitoring: Bool
}

/// On-device storage only (CLAUDE.md §1.4): colour metrics and outcomes as JSON in the app's
/// Documents folder. No images (developer patches are separate and opt-in, see DevLog).
@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var checks: [CheckRecord] = []
    @Published private(set) var baselines: [Int: Baseline] = [:]

    private struct Snapshot: Codable { var checks: [CheckRecord]; var baselines: [Int: Baseline] }
    private let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("scleracheck.json")

    private init() {
        if let data = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(Snapshot.self, from: data) {
            checks = s.checks; baselines = s.baselines
        }
    }

    private func save() {
        let s = Snapshot(checks: checks, baselines: baselines)
        if let data = try? JSONEncoder().encode(s) {
            try? data.write(to: url, options: [.atomic, .completeFileProtection])
        }
    }

    func add(_ r: CheckRecord) { checks.append(r); save() }
    func baseline(_ m: CaptureMode) -> Baseline? { baselines[m.rawValue] }
    func setBaseline(_ b: Baseline, for m: CaptureMode) { baselines[m.rawValue] = b; save() }
    func clearAll() { checks = []; baselines = [:]; save() }

    func validChecks(_ m: CaptureMode) -> [CheckRecord] {
        checks.filter { $0.outcome != .retake && !$0.isBaselineCapture && $0.mode == m }
    }
    var lastValid: CheckRecord? { checks.last { $0.outcome != .retake && !$0.isBaselineCapture } }
}

/// Small settings in UserDefaults.
enum Prefs {
    private static let d = UserDefaults.standard
    static var disclaimerAccepted: Bool { get { d.bool(forKey: "disclaimer") } set { d.set(newValue, forKey: "disclaimer") } }
    /// Developer toggle (CLAUDE.md §9). Hidden in Release builds.
    static var devMode: Bool { get { d.bool(forKey: "dev") } set { d.set(newValue, forKey: "dev") } }
    static var captureMode: CaptureMode {
        get { CaptureMode(rawValue: d.object(forKey: "mode") as? Int ?? CaptureMode.frontScreen.rawValue) ?? .frontScreen }
        set { d.set(newValue.rawValue, forKey: "mode") }
    }
    static var checkFrequencyDays: Int { get { max(1, d.integer(forKey: "freq")) } set { d.set(newValue, forKey: "freq") } }
    static var ccm: ColourCorrection {
        get { (d.data(forKey: "ccm")).flatMap { try? JSONDecoder().decode(ColourCorrection.self, from: $0) } ?? ColourCorrection() }
        set { d.set(try? JSONEncoder().encode(newValue), forKey: "ccm") }
    }
    static var devLabel = ""

    static var devToolsAvailable: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}
