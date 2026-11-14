import Darwin  // not Foundation: the CLT ship an empty _Testing_Foundation overlay
import Testing
@testable import ScleraCore

// Port of the Android JVM tests (ColourMathTest + PipelineTest). Same known values, same synthetic eye,
// so the two platforms are held to the same numbers.

@Suite struct ColourMathTests {
    func lab(_ r: Int, _ g: Int, _ b: Int) -> (l: Double, a: Double, b: Double) { ColourMath.srgb8ToLab(r, g, b) }

    @Test func whiteIsL100Neutral() {
        let l = lab(255, 255, 255)
        #expect(abs(l.l - 100) < 0.01); #expect(abs(l.a) < 0.05); #expect(abs(l.b) < 0.05)
    }
    @Test func pureRed() { let l = lab(255, 0, 0); #expect(abs(l.l - 53.24) < 0.05); #expect(abs(l.a - 80.09) < 0.1); #expect(abs(l.b - 67.20) < 0.1) }
    @Test func pureYellow() { let l = lab(255, 255, 0); #expect(abs(l.l - 97.14) < 0.05); #expect(abs(l.a + 21.55) < 0.1); #expect(abs(l.b - 94.48) < 0.1) }
    @Test func midGrey() { #expect(abs(lab(128, 128, 128).l - 53.59) < 0.05) }
    @Test func srgbRoundTrip() {
        for v in [0.0, 0.01, 0.04045, 0.2, 0.5, 0.9, 1.0] { #expect(abs(ColourMath.linearToSrgb(ColourMath.srgbToLinear(v)) - v) < 1e-6) }
    }
    @Test func differencingRemovesAmbient() {
        let amb = [0.10, 0.08, 0.04], truth = [0.40, 0.40, 0.38]
        let out = ColourMath.torchOnly((0..<3).map { amb[$0] + truth[$0] }, amb)
        for i in 0..<3 { #expect(abs(out[i] - truth[i]) < 1e-12) }
    }
    @Test func ccmFitRecoversKnownMatrix() {
        let truth = ColourCorrection([1.2, -0.1, 0, 0.05, 0.9, 0.05, 0, -0.2, 1.3])
        let measured: [[Double]] = [[0.8, 0.1, 0.1], [0.1, 0.7, 0.2], [0.1, 0.2, 0.9], [0.5, 0.5, 0.5], [0.9, 0.8, 0.2], [0.3, 0.6, 0.8]]
        let fit = ColourCorrection.fit(measured: measured, reference: measured.map(truth.apply))!
        for i in 0..<9 { #expect(abs(fit.m[i] - truth.m[i]) < 1e-9) }
    }
}

@Suite struct PipelineTests {
    let w = 200, h = 120
    var eye: EyeGeometry {
        let contour = (0..<16).map { k -> Pt in let t = Double(k) * 2 * .pi / 16; return Pt(100 + 80 * cos(t), 60 + 35 * sin(t)) }
        return EyeGeometry(contour: contour, irisCenter: Pt(100, 60), irisRadius: 22, innerCorner: Pt(20, 60),
                           outerCorner: Pt(180, 60), upperLid: Pt(100, 25), lowerLid: Pt(100, 95))
    }

    func enc(_ v: Double) -> UInt32 { UInt32(min(max(Int(ColourMath.linearToSrgb(min(max(v, 0), 1)) * 255), 0), 255)) }

    /// Deterministic LCG so the noise is reproducible.
    struct Rng { var s: UInt64; mutating func next() -> Double { s = s &* 6364136223846793005 &+ 1442695040888963407; return Double(s >> 11) / Double(1 << 53) } }

    func frame(_ sclera: [Double], light: [Double], ambient: [Double], seed: UInt64) -> EyeFrame {
        var rng = Rng(s: seed &+ 1)
        var px = [UInt32](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let x = Double(i % w), y = Double(i / w)
            let inIris = (x - 100) * (x - 100) + (y - 60) * (y - 60) < 22 * 22
            let refl = inIris ? [0.05, 0.03, 0.02] : sclera
            let n = 1 + (rng.next() - 0.5) * 0.04
            px[i] = 0xFF00_0000 | enc(refl[0] * (light[0] + ambient[0]) * n) << 16 | enc(refl[1] * (light[1] + ambient[1]) * n) << 8 | enc(refl[2] * (light[2] + ambient[2]) * n)
        }
        return EyeFrame(img: RgbImage(width: w, height: h, pixels: px), eye: eye, timestamp: 0)
    }

    func measure(_ sclera: [Double], ambient: [Double], power: Double = 0.7) -> SideMeasurement {
        let torch = [power, power, power * 0.95], none = [0.0, 0, 0]
        let amb = (0..<3).map { frame(sclera, light: none, ambient: ambient, seed: UInt64($0)) }
        let lit = (0..<3).map { frame(sclera, light: torch, ambient: ambient, seed: UInt64(10 + $0)) }
        return ScleraMeasurer.measure(ambient: amb, lit: lit, ccm: ColourCorrection())
    }

    @Test func segmentationExcludesIris() {
        let f = frame([0.8, 0.78, 0.7], light: [0.7, 0.7, 0.7], ambient: [0, 0, 0], seed: 1)
        let m = ScleraSegmenter.segment(f.img, f.eye)
        #expect(m.clean.count > 2000)
        for i in m.clean { let x = i % w, y = i / w; #expect((x - 100) * (x - 100) + (y - 60) * (y - 60) > 22 * 22) }
    }

    @Test func lightingInvarianceAfterDifferencing() {
        let s = [0.80, 0.77, 0.66]
        let neutral = measure(s, ambient: [0.02, 0.02, 0.02])
        let warm = measure(s, ambient: [0.20, 0.13, 0.05])
        let cool = measure(s, ambient: [0.10, 0.12, 0.16])
        #expect(abs(warm.bStar - neutral.bStar) < 1.5)
        #expect(abs(cool.bStar - neutral.bStar) < 1.5)
        #expect(abs(warm.naiveBStar! - cool.naiveBStar!) > 5, "uncorrected b* should drift with room light")
    }

    @Test func exposureNormalisationRemovesDistanceEffect() {
        let s = [0.80, 0.77, 0.66]
        #expect(abs(measure(s, ambient: [0.05, 0.05, 0.05], power: 0.8).bStar - measure(s, ambient: [0.05, 0.05, 0.05], power: 0.45).bStar) < 1.0)
    }

    @Test func yellowerScleraIsMonotonic() {
        let bs = [0.74, 0.68, 0.60, 0.52].map { measure([0.80, 0.77, $0], ambient: [0.05, 0.05, 0.05]).bStar }
        for i in 1..<bs.count { #expect(bs[i] > bs[i - 1]) }
    }

    @Test func brightRoomFlagsWeakTorch() {
        let m = measure([0.8, 0.77, 0.66], ambient: [0.9, 0.9, 0.9], power: 0.1)
        #expect(QualityGate.evaluate(m, m).reasons.contains(.torchTooWeak))
    }

    @Test func lashDominatedRegionStillKeepsSclera() {
        let sclera = [0.80, 0.77, 0.66], lash = [0.04, 0.03, 0.03]
        let cut = 60 + 35 * 0.4
        var px = [UInt32](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let refl = Double(i / w) < cut ? lash : sclera
            px[i] = 0xFF00_0000 | enc(refl[0] * 0.7) << 16 | enc(refl[1] * 0.7) << 8 | enc(refl[2] * 0.7)
        }
        let m = ScleraSegmenter.segment(RgbImage(width: w, height: h, pixels: px), eye)
        #expect(m.clean.count > 300)
        #expect(m.clean.allSatisfy { Double($0 / w) >= cut })
    }

    @Test func blinkedBurstGivesEyesClosed() {
        var closed = eye; closed.upperLid = Pt(100, 59); closed.lowerLid = Pt(100, 61)
        let f = frame([0.8, 0.77, 0.66], light: [0.7, 0.7, 0.7], ambient: [0.02, 0.02, 0.02], seed: 3)
        let c = EyeFrame(img: f.img, eye: closed, timestamp: 0)
        #expect(QualityGate.evaluateSide(ScleraMeasurer.measure(ambient: [f], lit: [c, c], ccm: ColourCorrection())).reasons == [.eyesClosed])
    }

    let ok = QualityResult([])
    @Test func retakeNeverForcesVerdict() {
        #expect(DecisionEngine.decide(QualityResult([.blurry]), bLeft: 10, bRight: 10, baselineB: 10, previousDeltas: []).outcome == .retake)
    }
    @Test func singleRiseIsNotReferralButTwoAre() {
        let base = 10.0, high = base + Thresholds.tChange + 1
        #expect(DecisionEngine.decide(ok, bLeft: high, bRight: high, baselineB: base, previousDeltas: []).outcome == .noConcern)
        #expect(DecisionEngine.decide(ok, bLeft: high, bRight: high, baselineB: base, previousDeltas: [Thresholds.tChange + 0.5]).outcome == .refer)
        #expect(DecisionEngine.decide(ok, bLeft: high, bRight: high, baselineB: base, previousDeltas: [Thresholds.tChange + 0.5, 0.2]).outcome == .noConcern)
    }
    @Test func absoluteThresholdRefersImmediately() {
        let v = Thresholds.tAbsolute + 0.5
        #expect(DecisionEngine.decide(ok, bLeft: v, bRight: v, baselineB: nil, previousDeltas: []).outcome == .refer)
    }
    @Test func nearBaselineIsNoConcern() {
        #expect(DecisionEngine.decide(ok, bLeft: 10.5, bRight: 11, baselineB: 10, previousDeltas: [5]).outcome == .noConcern)
    }
}
