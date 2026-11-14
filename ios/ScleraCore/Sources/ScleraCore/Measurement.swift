import Foundation

/// One captured frame (cropped to the eye) with its landmarks. Immutable after init; the lazily
/// computed sharpness is deterministic, so a racing first read just computes the same value twice.
public final class EyeFrame: @unchecked Sendable {
    public let img: RgbImage
    public let eye: EyeGeometry
    public let timestamp: Double
    public init(img: RgbImage, eye: EyeGeometry, timestamp: Double) { self.img = img; self.eye = eye; self.timestamp = timestamp }
    public lazy var sharpness: Double = Sharpness.ofEye(img, eye)
}

/// Result for one gaze position (port of Kotlin `SideMeasurement`).
public struct SideMeasurement: Codable, Sendable {
    public var bStar: Double
    public var aStar: Double
    /// L* of the light-only signal BEFORE exposure normalisation (depends on distance).
    public var lStarRaw: Double
    public var cleanPixels: Int
    public var glareFraction: Double
    public var vesselFraction: Double
    public var torchToAmbient: Double
    public var sharpness: Double
    public var gazeOffset: Double
    public var framesUsed: Int
    /// "Without correction" comparison for the lighting-invariance experiment (CLAUDE.md §6.1).
    public var naiveBStar: Double?
    public var ambientFrames: Int = 0
    public var torchFrames: Int = 0
    public var torchOpenFrames: Int = 0
}

public enum ScleraMeasurer {
    /// Eyes-open frames, sharpest first.
    public static func selectFrames(_ frames: [EyeFrame], keep: Int = 3) -> [EyeFrame] {
        Array(frames.filter { $0.eye.openness >= Thresholds.minOpenness }
            .sorted { $0.sharpness > $1.sharpness }
            .prefix(keep))
    }

    /// Mean linear RGB over the clean sclera of the ambient frames. Region mean rather than per-pixel
    /// subtraction: the hand moves between bursts, and ambient light over ~1 cm of sclera is smooth.
    public static func ambientMean(_ ambient: [EyeFrame]) -> [Double]? {
        let lut = ColourMath.linearLUT
        var acc = [0.0, 0.0, 0.0]; var n = 0
        for f in ambient {
            for i in ScleraSegmenter.segment(f.img, f.eye).clean {
                acc[0] += lut[f.img.r(i)]; acc[1] += lut[f.img.g(i)]; acc[2] += lut[f.img.b(i)]; n += 1
            }
        }
        guard n > 0 else { return nil }
        return acc.map { $0 / Double(n) }
    }

    /// lit − ambient (differencing) → colour correction → exposure normalisation → Lab → median b*.
    public static func measure(ambient: [EyeFrame], lit: [EyeFrame], ccm: ColourCorrection) -> SideMeasurement {
        let ambSel = ambient.filter { $0.eye.openness >= Thresholds.minOpenness }
        let litOpen = lit.filter { $0.eye.openness >= Thresholds.minOpenness }.count
        let sel = selectFrames(lit, keep: 4)
        guard !sel.isEmpty else {
            return SideMeasurement(bStar: .nan, aStar: .nan, lStarRaw: .nan, cleanPixels: 0, glareFraction: 0,
                                   vesselFraction: 0, torchToAmbient: 0, sharpness: 0, gazeOffset: lit.first?.eye.gazeOffset ?? 0,
                                   framesUsed: 0, naiveBStar: nil, ambientFrames: ambient.count, torchFrames: lit.count, torchOpenFrames: litOpen)
        }
        let amb = ambientMean(ambSel.isEmpty ? ambient : ambSel) ?? [0, 0, 0]
        let lut = ColourMath.linearLUT
        var rgbs: [[Double]] = []
        var glare = 0.0, vessel = 0.0
        for f in sel {
            let m = ScleraSegmenter.segment(f.img, f.eye)
            glare += m.glareFraction; vessel += m.vesselFraction
            for i in m.clean {
                let t = [lut[f.img.r(i)], lut[f.img.g(i)], lut[f.img.b(i)]]
                rgbs.append(ccm.apply(ColourMath.torchOnly(t, amb)))
            }
        }
        let nSel = Double(sel.count)
        let naive = selectFrames(ambSel, keep: 1).first.flatMap { liveEstimate($0) }
        guard !rgbs.isEmpty else {
            return SideMeasurement(bStar: .nan, aStar: .nan, lStarRaw: .nan, cleanPixels: 0, glareFraction: glare / nSel,
                                   vesselFraction: vessel / nSel, torchToAmbient: 0, sharpness: sel[0].sharpness,
                                   gazeOffset: sel[0].eye.gazeOffset, framesUsed: sel.count, naiveBStar: naive,
                                   ambientFrames: ambient.count, torchFrames: lit.count, torchOpenFrames: litOpen)
        }
        let ys = rgbs.map { ColourMath.luminance($0[0], $0[1], $0[2]) }
        let medY = ColourMath.median(ys)
        let ambY = ColourMath.luminance(amb[0], amb[1], amb[2])
        let scale = medY > 1e-6 ? Thresholds.normalisedY / medY : 1
        var bs: [Double] = [], as_: [Double] = []
        bs.reserveCapacity(rgbs.count); as_.reserveCapacity(rgbs.count)
        for p in rgbs {
            let lab = ColourMath.linearRgbToLab(p[0] * scale, p[1] * scale, p[2] * scale)
            as_.append(lab.a); bs.append(lab.b)
        }
        return SideMeasurement(
            bStar: ColourMath.median(bs), aStar: ColourMath.median(as_),
            lStarRaw: ColourMath.xyzToLab(0, medY, 0).l,
            cleanPixels: rgbs.count / sel.count,
            glareFraction: glare / nSel, vesselFraction: vessel / nSel,
            torchToAmbient: ambY > 1e-6 ? medY / ambY : 99,
            sharpness: sel[0].sharpness, gazeOffset: sel[0].eye.gazeOffset, framesUsed: sel.count,
            naiveBStar: naive, ambientFrames: ambient.count, torchFrames: lit.count, torchOpenFrames: litOpen)
    }

    /// Single-frame, uncorrected b* for the live developer readout (drifts with room light by design).
    public static func liveEstimate(_ f: EyeFrame) -> Double? {
        let m = ScleraSegmenter.segment(f.img, f.eye)
        guard m.clean.count >= 50 else { return nil }
        let lut = ColourMath.linearLUT
        return ColourMath.median(m.clean.map { ColourMath.linearRgbToLab(lut[f.img.r($0)], lut[f.img.g($0)], lut[f.img.b($0)]).b })
    }
}

// MARK: - Quality gate (CLAUDE.md §4)

public enum RetakeReason: String, Codable, CaseIterable, Sendable {
    case notDetected, eyesClosed, tooFewPixels, blurry, torchTooWeak, tooMuchGlare, sidesDisagree, tooRed

    public var message: String {
        switch self {
        case .notDetected: return "We lost track of your eye. Hold the phone steady with your eye in the middle of the screen."
        case .eyesClosed: return "Your eye closed when the light came on. Try to keep it wide open for a second after the countdown."
        case .tooFewPixels: return "Not enough of the white of your eye was visible. Open your eyes wide and look further to the side."
        case .blurry: return "The picture was blurry. Hold the phone still until you hear the beep."
        case .torchTooWeak: return "The room is too bright for the light to work. Move away from windows or direct sunlight."
        case .tooMuchGlare: return "There was too much reflection on your eye. Tilt the phone slightly and try again."
        case .sidesDisagree: return "The two measurements didn't match. Please try again."
        case .tooRed: return "Your eye looks red. Please try again later. If the redness persists, contact your GP."
        }
    }
}

public struct QualityResult: Sendable {
    public let reasons: [RetakeReason]
    public var passed: Bool { reasons.isEmpty }
    public init(_ reasons: [RetakeReason]) { self.reasons = reasons }
}

public enum QualityGate {
    public static func evaluateSide(_ s: SideMeasurement?) -> QualityResult {
        guard let s, s.torchFrames > 0 else { return QualityResult([.notDetected]) }
        if s.framesUsed == 0 { return QualityResult([.eyesClosed]) }
        var r: [RetakeReason] = []
        if s.vesselFraction > Thresholds.maxVesselFraction { r.append(.tooRed) }
        if s.cleanPixels < Thresholds.minCleanPixels || s.bStar.isNaN { r.append(.tooFewPixels) }
        if s.sharpness < Thresholds.minSharpness { r.append(.blurry) }
        if s.torchToAmbient < Thresholds.minTorchRatio { r.append(.torchTooWeak) }
        if s.glareFraction > Thresholds.maxGlareFraction { r.append(.tooMuchGlare) }
        return QualityResult(r)
    }

    public static func evaluate(_ a: SideMeasurement?, _ b: SideMeasurement?) -> QualityResult {
        var r: [RetakeReason] = []
        for reason in evaluateSide(a).reasons + evaluateSide(b).reasons where !r.contains(reason) { r.append(reason) }
        if r.isEmpty, let a, let b, abs(a.bStar - b.bStar) > Thresholds.maxLRDisagreement { r.append(.sidesDisagree) }
        return QualityResult(r)
    }
}

// MARK: - Decision rule (CLAUDE.md §5)

public enum Outcome: String, Codable, Sendable { case noConcern, refer, retake }

public struct Decision: Sendable {
    public let outcome: Outcome
    public let bStar: Double?
    public let delta: Double?
    public var explanation: String
    public let retakeReasons: [RetakeReason]
}

public enum DecisionEngine {
    /// REFER if Δ > T_change on `consecutiveForRefer` consecutive valid checks (incl. today), or b* > T_absolute.
    /// RETAKE whenever the quality gate fails — never force a verdict.
    /// - Parameter previousDeltas: earlier valid checks' deltas, most recent LAST.
    public static func decide(_ q: QualityResult, bLeft: Double?, bRight: Double?, baselineB: Double?, previousDeltas: [Double]) -> Decision {
        guard q.passed, let l = bLeft, let r = bRight else {
            return Decision(outcome: .retake, bStar: nil, delta: nil, explanation: "The capture wasn't good enough to give a result.", retakeReasons: q.reasons)
        }
        let today = (l + r) / 2
        if today > Thresholds.tAbsolute {
            return Decision(outcome: .refer, bStar: today, delta: baselineB.map { today - $0 },
                            explanation: String(format: "Yellowness index %.1f is above the safety threshold %.1f.", today, Thresholds.tAbsolute), retakeReasons: [])
        }
        guard let base = baselineB else {
            return Decision(outcome: .noConcern, bStar: today, delta: nil,
                            explanation: "No baseline recorded yet, so only the safety threshold was checked.", retakeReasons: [])
        }
        let delta = today - base
        let needed = Thresholds.consecutiveForRefer
        let recent = Array(previousDeltas.suffix(needed - 1)) + [delta]
        if recent.count >= needed && recent.allSatisfy({ $0 > Thresholds.tChange }) {
            return Decision(outcome: .refer, bStar: today, delta: delta,
                            explanation: String(format: "Yellowness has risen by more than %.1f from your baseline on %d checks in a row.", Thresholds.tChange, needed), retakeReasons: [])
        }
        if delta > Thresholds.tChange {
            return Decision(outcome: .noConcern, bStar: today, delta: delta,
                            explanation: "Slightly higher than your baseline today. Please repeat the check tomorrow so we can confirm.", retakeReasons: [])
        }
        return Decision(outcome: .noConcern, bStar: today, delta: delta, explanation: "Close to your baseline.", retakeReasons: [])
    }
}
