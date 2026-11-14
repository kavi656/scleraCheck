import AVFoundation
import AudioToolbox
import UIKit
import ScleraCore

/// Audio + haptic guidance (CLAUDE.md §0). Spoken cues are throttled so the patient isn't flooded.
@MainActor
final class Guide {
    private let synth = AVSpeechSynthesizer()
    private var lastCue: String?
    private var lastCueAt = Date.distantPast
    private let haptic = UIImpactFeedbackGenerator(style: .medium)
    private let notify = UINotificationFeedbackGenerator()

    init() {
        // Speak even with the ring/silent switch on; duck other audio.
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    func cue(_ text: String, repeatAfter: TimeInterval = 2.5) {
        let now = Date()
        if text == lastCue && now.timeIntervalSince(lastCueAt) < repeatAfter { return }
        if text != lastCue && lastCue != nil && now.timeIntervalSince(lastCueAt) < 1.2 { return }
        say(text)
    }

    func say(_ text: String) {
        lastCue = text; lastCueAt = Date()
        synth.stopSpeaking(at: .immediate)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-GB")
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        synth.speak(u)
    }

    func done() {
        AudioServicesPlaySystemSound(1057) // short "tock"
        notify.notificationOccurred(.success)
    }

    func tick() { haptic.impactOccurred() }

    func stop() { synth.stopSpeaking(at: .immediate) }
}

/// Developer toggle only (CLAUDE.md §9): masked sclera patches + CSV in Documents/debug, visible in
/// Finder (iPhone → Files → ScleraCheck) or the Files app. No face images: non-sclera pixels are black.
enum DevLog {
    static var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("debug")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func maskedPatch(_ lit: [EyeFrame]) -> UIImage? {
        guard let f = ScleraMeasurer.selectFrames(lit, keep: 1).first else { return nil }
        let mask = ScleraSegmenter.segment(f.img, f.eye)
        var out = [UInt32](repeating: 0xFF00_0000, count: f.img.pixels.count)
        for i in mask.clean { out[i] = f.img.pixels[i] }
        return image(from: RgbImage(width: f.img.width, height: f.img.height, pixels: out))
    }

    static func image(from img: RgbImage) -> UIImage? {
        var px = img.pixels
        let cs = CGColorSpaceCreateDeviceRGB()
        let info = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
        return px.withUnsafeMutableBytes { buf -> UIImage? in
            guard let ctx = CGContext(data: buf.baseAddress, width: img.width, height: img.height, bitsPerComponent: 8,
                                      bytesPerRow: img.width * 4, space: cs, bitmapInfo: info),
                  let cg = ctx.makeImage() else { return nil }
            return UIImage(cgImage: cg)
        }
    }

    private static let header = "timestamp,row,label,side,attempt,result,bStar,aStar,lStarRaw,naiveBStar,cleanPx,glare,vessel,torchRatio,sharpness,gaze,ambientFrames,torchFrames,torchOpenFrames,ev,zoom,mode\n"

    private static func append(_ line: String) {
        let url = dir.appendingPathComponent("log_v2.csv")
        if !FileManager.default.fileExists(atPath: url.path) { try? header.write(to: url, atomically: true, encoding: .utf8) }
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close()
        }
    }

    static func side(ts: Int, side: Int, attempt: Int, lit: [EyeFrame], m: SideMeasurement?, q: QualityResult, ev: Float, zoom: Double, mode: CaptureMode) {
        if let img = maskedPatch(lit), let png = img.pngData() {
            try? png.write(to: dir.appendingPathComponent("\(ts)_side\(side)_try\(attempt).png"))
        }
        func s(_ v: Double?) -> String { v.map { $0.isNaN ? "nan" : String($0) } ?? "" }
        let result = q.passed ? "pass" : q.reasons.map(\.rawValue).joined(separator: "|")
        let cols: [String] = ["\(ts)", "side", Prefs.devLabel, "\(side)", "\(attempt)", result, s(m?.bStar), s(m?.aStar), s(m?.lStarRaw),
                              s(m?.naiveBStar), "\(m?.cleanPixels ?? 0)", s(m?.glareFraction), s(m?.vesselFraction), s(m?.torchToAmbient),
                              s(m?.sharpness), s(m?.gazeOffset), "\(m?.ambientFrames ?? 0)", "\(m?.torchFrames ?? 0)", "\(m?.torchOpenFrames ?? 0)",
                              String(format: "%.2f", ev), String(format: "%.2f", zoom), mode.label]
        append(cols.joined(separator: ",") + "\n")
    }

    static func check(ts: Int, d: Decision, mode: CaptureMode) {
        let reasons = d.retakeReasons.map(\.rawValue).joined(separator: "|")
        append("\(ts),check,\(Prefs.devLabel),,,\(d.outcome.rawValue)\(reasons.isEmpty ? "" : ":" + reasons),\(d.bStar.map { String($0) } ?? ""),,,,,,,,,,,,,,,\(mode.label)\n")
    }
}
