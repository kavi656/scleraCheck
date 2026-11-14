import AVFoundation
import UIKit
import ScleraCore

enum CheckPhase {
    case loading
    case intro(lightAdvice: String?)
    case guiding(step: Int, text: String)
    case capturing(step: Int)
    case processing
    case finished(Finished)

    struct Finished {
        let decision: Decision
        let first: SideMeasurement?
        let second: SideMeasurement?
        let isBaseline: Bool
        let patches: [UIImage?]
        let attempts: [Int]
    }
}

enum ScreenLight { case normal, black, white }

struct LiveInfo {
    var eye: EyeGeometry?
    var imageSize = CGSize(width: 720, height: 1280)
    var distanceMM: Double?
    var sharpness: Double?
    var liveB: Double?
    var zoom: Double = 1
}

/// Per-frame analysis on the camera's video queue. All mutable state is behind `lock`;
/// the check flow (main actor) reads snapshots. Port of the analysis half of Android `CheckViewModel`.
final class FrameAnalyzer {
    private let lock = NSLock()
    private let tracker = EyeTracker()
    private let camera: CameraController
    private let caps: CameraController.Caps
    let devMode: Bool
    let distMin: Double, distMax: Double

    // Guarded state
    private var targetEyeIndex: Int?
    private var lastIris: Pt?
    private var _lastEye: EyeGeometry?
    private var _stillSince: Double = 0
    private var _stableSince: Double?
    private var _positioned = false
    private var _guiding = false
    private var _sink: [EyeFrame]?
    private var smoothDist: Double?
    private var lastFocusAt: Double = 0
    private var zoomCmd = 1.0, zoomLosses = 0, zoomDisabled = false, lastEyeSeenAt: Double = 0
    private var frameCount = 0
    private var imageSize = CGSize(width: 720, height: 1280)

    /// Called on main with live info and, while guiding, the cue to show/speak (nil = good position).
    var onUpdate: ((LiveInfo, String?) -> Void)?

    init(camera: CameraController, caps: CameraController.Caps, devMode: Bool) {
        self.camera = camera; self.caps = caps; self.devMode = devMode
        let band = caps.autoFocus ? Thresholds.distanceBand(minFocusMM: caps.minFocusMM) : Thresholds.fixedFocusBandMM
        distMin = band.0; distMax = band.1
    }

    /// Wire camera frames to this analyzer. Formed here (non-isolated) because frames arrive on the video queue.
    func attach() {
        camera.onFrame = { [weak self] pb, ts in self?.process(pb, ts) }
    }

    @discardableResult
    private func locked<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }

    var lastEye: EyeGeometry? { locked { _lastEye } }
    var positioned: Bool { locked { _positioned } }
    var isStill: Bool { locked { _stillSince != 0 } }
    var currentImageSize: CGSize { locked { imageSize } }

    func setGuiding(_ on: Bool) { locked { _guiding = on; if on { _positioned = false; _stableSince = nil } } }
    func resetTarget() { locked { targetEyeIndex = nil } }
    func startCollecting() { locked { _sink = [] } }
    func collected() -> [EyeFrame] { locked { _sink ?? [] } }
    func stopCollecting() -> [EyeFrame] { locked { let s = _sink ?? []; _sink = nil; return s } }

    func process(_ pb: CVPixelBuffer, _ ts: Double) {
        let w = Double(CVPixelBufferGetWidth(pb)), h = Double(CVPixelBufferGetHeight(pb))
        let eyes = tracker.detect(pb)

        lock.lock()
        imageSize = CGSize(width: w, height: h)
        var eye: EyeGeometry?
        if let eyes {
            let idx = targetEyeIndex.flatMap { $0 < eyes.count ? $0 : nil } ??
                eyes.indices.min(by: { hypot(eyes[$0].irisCenter.x / w - 0.5, eyes[$0].irisCenter.y / h - 0.5) <
                                       hypot(eyes[$1].irisCenter.x / w - 0.5, eyes[$1].irisCenter.y / h - 0.5) })!
            eye = eyes[idx]
            if _guiding && targetEyeIndex == nil { targetEyeIndex = idx }
        }
        _lastEye = eye
        let zoom = camera.zoom
        // Distance from eye width (pinhole: Z = f·D/d), zoom multiplies the effective focal length.
        let dist = eye.map { caps.focalPx1x * zoom * Thresholds.eyeWidthMM / max($0.width, 1) }
        let still = eye != nil && lastIris.map { p in eye!.irisCenter.dist(p) / max(eye!.irisRadius, 1) < Thresholds.stillMaxMove } == true
        lastIris = eye?.irisCenter
        if still { if _stillSince == 0 { _stillSince = ts } } else { _stillSince = 0 }
        let sinkActive = _sink != nil
        let guiding = _guiding
        frameCount += 1
        let doLive = devMode && eye != nil && frameCount % 4 == 0
        lock.unlock()

        // Crop to the eye only (CLAUDE.md §1.4) for burst frames and the live dev readout.
        var cropped: EyeFrame?
        if let eye, sinkActive || doLive {
            cropped = Self.cropEye(pb, eye, ts)
            if sinkActive, let c = cropped { locked { _sink?.append(c) } }
        }
        var info = LiveInfo(eye: eye, imageSize: imageSize, distanceMM: dist, zoom: zoom)
        if doLive, let c = cropped { info.sharpness = c.sharpness; info.liveB = ScleraMeasurer.liveEstimate(c) }

        var cue: String?
        if guiding {
            driveFocus(eye: eye, dist: dist, w: w, h: h, ts: ts)
            driveZoom(eye: eye, ts: ts)
            cue = positionCue(eye: eye, dist: dist, still: still, w: w, h: h, ts: ts)
        }
        DispatchQueue.main.async { self.onUpdate?(info, guiding ? (cue ?? "") : nil) }
    }

    private func driveFocus(eye: EyeGeometry?, dist: Double?, w: Double, h: Double, ts: Double) {
        guard let eye, let dist else { locked { smoothDist = nil }; return }
        let doFocus: Bool = locked {
            smoothDist = smoothDist.map { 0.6 * $0 + 0.4 * dist } ?? dist
            if ts - lastFocusAt > 500 { lastFocusAt = ts; return true }
            return false
        }
        guard doFocus, caps.autoFocus else { return }
        let nx = eye.irisCenter.x / w, ny = eye.irisCenter.y / h, size = CGSize(width: w, height: h)
        Task { @MainActor [camera] in
            if let p = camera.devicePoint(nx: nx, ny: ny, imageSize: size) { camera.setFocusPoint(p) }
        }
    }

    /// Acquire at 1× (whole face visible), ramp to the target zoom near the target distance; drop back if lost.
    private func driveZoom(eye: EyeGeometry?, ts: Double) {
        let target = min(Thresholds.zoomTarget, caps.maxZoom)
        guard target > 1.05 else { return }
        var newZoom: Double?
        locked {
            if eye != nil { lastEyeSeenAt = ts }
            if eye == nil {
                if zoomCmd > 1, ts - lastEyeSeenAt > Thresholds.zoomLostMS {
                    zoomCmd = 1; newZoom = 1; zoomLosses += 1
                    if zoomLosses >= 2 { zoomDisabled = true }
                }
                return
            }
            if !zoomDisabled, let sd = smoothDist, sd < distMax * Thresholds.zoomStartFactor, zoomCmd < target {
                zoomCmd = min(target, zoomCmd + Thresholds.zoomStep); newZoom = zoomCmd
            }
        }
        if let z = newZoom { camera.setZoom(z) }
    }

    private func positionCue(eye: EyeGeometry?, dist: Double?, still: Bool, w: Double, h: Double, ts: Double) -> String? {
        let front = camera.front
        guard let eye, let dist else {
            locked { _stableSince = nil }
            return front ? "Hold the phone like a selfie so your face is on the screen." : "Point the back camera at the eye. Start at arm's length, then bring it closer."
        }
        let offX = (eye.irisCenter.x / w - 0.5) / 0.5, offY = (eye.irisCenter.y / h - 0.5) / 0.5
        let msg: String?
        if dist > distMax { msg = "Closer" }
        else if dist < distMin { msg = "A bit further away" }
        else if abs(offX) > Thresholds.centreTolerance || abs(offY) > Thresholds.centreTolerance {
            msg = front ? "Put your eye in the middle of the screen" : "Keep the camera in front of the eye"
        } else if !still { msg = "Hold still" }
        else { msg = nil }
        return locked {
            if msg != nil { _stableSince = nil; return msg }
            let since = _stableSince ?? ts
            _stableSince = since
            let target = min(Thresholds.zoomTarget, caps.maxZoom)
            let zoomSettled = zoomDisabled || target <= 1.05 || abs(camera.zoom - target) < 0.05
            if ts - since >= Thresholds.stableMS && zoomSettled { _positioned = true }
            return nil
        }
    }

    static func cropEye(_ pb: CVPixelBuffer, _ eye: EyeGeometry, _ ts: Double) -> EyeFrame {
        let W = CVPixelBufferGetWidth(pb), H = CVPixelBufferGetHeight(pb)
        let b = eye.bounds, m = Int(eye.width * 0.2)
        let x0 = min(max(b.0 - m, 0), W - 1), y0 = min(max(b.1 - m, 0), H - 1)
        let x1 = min(max(b.2 + m, x0 + 1), W), y1 = min(max(b.3 + m, y0 + 1), H)
        let img = PixelBufferUtil.crop(pb, x0: x0, y0: y0, w: x1 - x0, h: y1 - y0)
        return EyeFrame(img: img, eye: eye.shifted(dx: -Double(x0), dy: -Double(y0)), timestamp: ts)
    }
}

/// The check flow (main actor). Port of the flow half of Android `CheckViewModel`.
@MainActor
final class CheckSession: ObservableObject {
    @Published var phase: CheckPhase = .loading
    @Published var live = LiveInfo()
    @Published var screenLight: ScreenLight = .normal
    @Published var caps: CameraController.Caps?

    let mode = Prefs.captureMode
    var front: Bool { mode == .frontScreen }
    let isBaseline: Bool
    let devMode = Prefs.devMode && Prefs.devToolsAvailable
    let camera: CameraController
    private let guide = Guide()
    private var analyzer: FrameAnalyzer?
    private var task: Task<Void, Never>?

    init(isBaseline: Bool) {
        self.isBaseline = isBaseline
        camera = CameraController(front: Prefs.captureMode == .frontScreen)
    }

    var distBand: (Double, Double)? { analyzer.map { ($0.distMin, $0.distMax) } }

    func startCamera() {
        camera.start { [weak self] caps in
            guard let self else { return }
            guard let caps else { self.phase = .intro(lightAdvice: "Couldn't open the camera."); return }
            self.caps = caps
            let a = FrameAnalyzer(camera: self.camera, caps: caps, devMode: self.devMode)
            a.onUpdate = { [weak self] info, cue in
                guard let self else { return }
                self.live = info
                if let cue, case .guiding(let step, _) = self.phase {
                    if cue.isEmpty { self.phase = .guiding(step: step, text: "Good. Hold still…") }
                    else { self.phase = .guiding(step: step, text: cue); self.guide.cue(cue) }
                }
            }
            a.attach()
            self.analyzer = a
            self.phase = .intro(lightAdvice: nil)
            // Give auto-exposure a moment, then advise on room light.
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                guard let self, case .intro = self.phase else { return }
                self.phase = .intro(lightAdvice: self.lightAdvice())
            }
        }
    }

    /// Typical indoor 100–500 lux; window daylight 1,000–10,000+ (CIBSE/SLL ranges).
    private func lightAdvice() -> String? {
        guard let lux = camera.estimatedLux() else { return nil }
        if lux > 2000 { return "It's very bright here. Please move away from the window or out of direct sunlight." }
        if lux < 15 { return "It's too dark here. Please turn on a room light." }
        return nil
    }

    func start() {
        guard task == nil, analyzer != nil else { return }
        task = Task { await run() }
    }

    func cancel() {
        task?.cancel(); task = nil
        analyzer?.setGuiding(false)
        _ = analyzer?.stopCollecting()
        screenLight = .normal
        camera.unlock()
        guide.stop()
    }

    func shutdown() {
        cancel()
        camera.stop()
    }

    private func sleep(_ ms: Double) async { try? await Task.sleep(nanoseconds: UInt64(ms * 1_000_000)) }

    private func run() async {
        guard let a = analyzer else { return }
        guide.say(front
            ? "Hold the phone like a selfie, about a hand's width from your face, with one eye in the middle of the screen. The screen will flash white briefly."
            : "A light on the back of the phone will turn on briefly. Point the back camera at the eye from about a hand's width away.")
        await sleep(4500)

        var results: [SideMeasurement?] = [nil, nil]
        var frames: [[EyeFrame]] = [[], []]
        var attempts = [0, 0]
        var firstGazeSign = 0.0
        a.resetTarget()
        let ts = Int(Date().timeIntervalSince1970 * 1000)

        for step in 0..<2 {
            for attempt in 1...Thresholds.maxSideAttempts {
                if Task.isCancelled { return }
                attempts[step] = attempt
                let (m, lit) = await captureSide(a, step: step, firstGazeSign: firstGazeSign)
                let q = QualityGate.evaluateSide(m)
                results[step] = m; frames[step] = lit
                if devMode {
                    let ev = camera.appliedEV, z = camera.zoom, md = mode
                    DispatchQueue.global(qos: .utility).async { DevLog.side(ts: ts, side: step, attempt: attempt, lit: lit, m: m, q: q, ev: ev, zoom: z, mode: md) }
                }
                if q.passed { break }
                if attempt < Thresholds.maxSideAttempts, let r = q.reasons.first {
                    guide.say("Let's do that side again. " + r.message)
                    phase = .guiding(step: step, text: "Again: " + r.message)
                    await sleep(3500)
                }
            }
            if step == 0 { firstGazeSign = (results[0]?.gazeOffset ?? a.lastEye?.gazeOffset ?? 0).sgn }
        }
        if Task.isCancelled { return }

        phase = .processing
        let store = Store.shared
        let q = QualityGate.evaluate(results[0], results[1])
        let baseline = store.baseline(mode)
        let prev = baseline.map { b in store.validChecks(mode).filter { $0.timestamp > b.timestamp }.compactMap { $0.bMean.map { $0 - b.bStar } } } ?? []
        var d = DecisionEngine.decide(q, bLeft: results[0]?.bStar, bRight: results[1]?.bStar,
                                      baselineB: isBaseline ? nil : baseline?.bStar, previousDeltas: prev)
        if isBaseline && d.outcome != .retake { d.explanation = "Baseline measurement captured." }
        store.add(CheckRecord(timestamp: Date(), outcome: d.outcome,
                              bLeft: results[0]?.bStar.nanToNil, bRight: results[1]?.bStar.nanToNil,
                              bMean: d.bStar, delta: d.delta, retakeReasons: q.reasons,
                              isBaselineCapture: isBaseline, mode: mode))
        var patches: [UIImage?] = []
        if devMode {
            let fr = frames
            patches = await Task.detached { fr.map { DevLog.maskedPatch($0) } }.value
            DevLog.check(ts: ts, d: d, mode: mode)
        }
        switch d.outcome {
        case .noConcern: guide.say("Check complete. No concern found today. You can look at the screen now.")
        case .refer: guide.say("Check complete. Signs of yellowing. Please look at the screen and contact your GP.")
        case .retake: guide.say("We need to try again. " + (d.retakeReasons.first ?? .notDetected).message)
        }
        phase = .finished(.init(decision: d, first: results[0], second: results[1], isBaseline: isBaseline, patches: patches, attempts: attempts))
        task = nil
    }

    /// Position → gaze → lock → countdown → ambient burst → light burst → measure, for one side.
    private func captureSide(_ a: FrameAnalyzer, step: Int, firstGazeSign: Double) async -> (SideMeasurement?, [EyeFrame]) {
        // 1) Position
        phase = .guiding(step: step, text: "Getting ready…")
        a.setGuiding(true)
        while !a.positioned { if Task.isCancelled { return (nil, []) }; await sleep(50) }
        a.setGuiding(false)

        // 2) Gaze
        let dir = step == 0 ? "left" : "right"
        phase = .guiding(step: step, text: "Look \(dir)")
        guide.say("Look \(dir), and keep your eyes wide open.")
        await sleep(1800)
        let gazeDeadline = Date().addingTimeInterval(4)
        while Date() < gazeDeadline {
            let g = a.lastEye?.gazeOffset ?? 0
            let okDir = step == 0 || firstGazeSign == 0 || g.sgn == -firstGazeSign
            if abs(g) >= Thresholds.minGazeOffset && okDir && a.isStill { break }
            guide.cue("Look further \(dir)")
            await sleep(80)
        }

        // 3) Lock, countdown, bursts
        phase = .capturing(step: step)
        guide.say("Hold still")
        let size = a.currentImageSize
        let p = a.lastEye.flatMap { camera.devicePoint(nx: $0.irisCenter.x / size.width, ny: $0.irisCenter.y / size.height, imageSize: size) }
        await camera.focusAndLock(devicePoint: p, ev: front ? Thresholds.screenExposureEV : Thresholds.torchExposureEV)
        guide.say(front ? "Flash in three, two, one. Eyes wide." : "Light in three, two, one. Eyes wide.")
        await sleep(2300)

        // Ambient burst immediately before the lit burst so the room light matches. Front: black screen.
        if front { screenLight = .black; await sleep(200) }
        let ambient = await collectOpen(a, timeoutMS: Thresholds.ambientBurstMS)
        let settle = front ? Thresholds.screenSettleMS : Thresholds.torchSettleMS
        if front { screenLight = .white } else { camera.setTorch(true) }
        await sleep(settle)
        // Light time = settle + collection, hard-capped below torchMaxMS (CLAUDE.md §1.3).
        let lit = await collectOpen(a, timeoutMS: Thresholds.torchMaxMS - settle - 150)
        screenLight = .normal
        camera.setTorch(false)
        camera.unlock()
        guide.done()

        let ccm = Prefs.ccm
        let m = await Task.detached(priority: .userInitiated) { ScleraMeasurer.measure(ambient: ambient, lit: lit, ccm: ccm) }.value
        await sleep(400)
        return (m, lit)
    }

    /// Collect until enough eyes-open frames (people blink when the light comes on), or time/frame cap.
    private func collectOpen(_ a: FrameAnalyzer, timeoutMS: Double) async -> [EyeFrame] {
        a.startCollecting()
        let deadline = Date().addingTimeInterval(timeoutMS / 1000)
        while Date() < deadline && !Task.isCancelled {
            let got = a.collected()
            if got.filter({ $0.eye.openness >= Thresholds.minOpenness }).count >= Thresholds.burstOpenFrames || got.count >= Thresholds.burstMaxFrames { break }
            await sleep(30)
        }
        return a.stopCollecting()
    }

    func confirmBaseline(_ b: Double, monitoring: Bool) {
        Store.shared.setBaseline(Baseline(timestamp: Date(), bStar: b, monitoring: monitoring), for: mode)
    }
}

extension Double {
    var nanToNil: Double? { isNaN ? nil : self }
    /// −1, 0 or +1.
    var sgn: Double { self > 0 ? 1 : (self < 0 ? -1 : 0) }
}
