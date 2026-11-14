import AVFoundation
import UIKit
import ScleraCore

/// Camera + light control (port of Android `CameraController`), on AVFoundation.
///
/// Capability policy (CLAUDE.md §9): every manual control is checked at runtime, with fallbacks.
///  - Lens: `.builtInWideAngleCamera` explicitly, so the phone never switches lens when we zoom
///    (a virtual multi-camera device would jump to the telephoto/ultra-wide, changing colour response).
///  - White balance: LOCKED to fixed daylight gains (5500 K) for the whole session, so the light-only
///    colour is comparable day to day (Android uses the DAYLIGHT AWB preset for the same reason).
///  - Exposure: biased down (headroom for the light), then LOCKED for both bursts — differencing needs
///    identical exposure in the ambient and lit frames.
///  - Focus: continuous AF with the point of interest on the eye while guiding, then locked.
final class CameraController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    struct Caps {
        let hasTorch: Bool
        let autoFocus: Bool
        /// Closest focus distance in mm (iOS 15+ `minimumFocusDistance`), nil if unknown.
        let minFocusMM: Double?
        let maxZoom: Double
        /// Focal length in pixels of the upright output image at 1× zoom.
        let focalPx1x: Double
        let describe: String
    }

    let session = AVCaptureSession()
    let front: Bool
    private(set) var device: AVCaptureDevice?
    private(set) var caps: Caps?
    private let videoQueue = DispatchQueue(label: "scleracheck.video", qos: .userInitiated)
    private let sessionQueue = DispatchQueue(label: "scleracheck.session")

    /// Upright (portrait), un-mirrored BGRA frames on the video queue.
    var onFrame: ((CVPixelBuffer, Double) -> Void)?
    /// Set by the preview view; used to convert image points to device points for focus/metering.
    weak var previewLayer: AVCaptureVideoPreviewLayer?

    /// EV bias actually applied for the most recent capture (for the CSV).
    private(set) var appliedEV: Float = 0

    init(front: Bool) { self.front = front; super.init() }

    var zoom: Double { Double(device?.videoZoomFactor ?? 1) }

    func start(_ done: @escaping (Caps?) -> Void) {
        sessionQueue.async { [self] in
            let c = configure()
            caps = c
            if c != nil { session.startRunning() }
            DispatchQueue.main.async { done(c) }
        }
    }

    func stop() {
        onFrame = nil
        if let d = device, d.hasTorch, (try? d.lockForConfiguration()) != nil { d.torchMode = .off; d.unlockForConfiguration() }
        sessionQueue.async { [session] in session.stopRunning() }
    }

    private func configure() -> Caps? {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .hd1280x720
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back),
              let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) else { return nil }
        session.addInput(input)

        let out = AVCaptureVideoDataOutput()
        out.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        out.alwaysDiscardsLateVideoFrames = true
        out.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(out) else { return nil }
        session.addOutput(out)
        if let conn = out.connection(with: .video) {
            if #available(iOS 17.0, *) {
                if conn.isVideoRotationAngleSupported(90) { conn.videoRotationAngle = 90 }
            } else if conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
            // Analysis frames are NOT mirrored (true geometry); only the preview is mirrored for selfies.
            if conn.isVideoMirroringSupported { conn.automaticallyAdjustsVideoMirroring = false; conn.isVideoMirrored = false }
        }
        device = dev

        if (try? dev.lockForConfiguration()) != nil {
            if dev.isWhiteBalanceModeSupported(.locked) {
                var g = dev.deviceWhiteBalanceGains(for: AVCaptureDevice.WhiteBalanceTemperatureAndTintValues(temperature: 5500, tint: 0))
                let mx = dev.maxWhiteBalanceGain
                g.redGain = min(max(g.redGain, 1), mx); g.greenGain = min(max(g.greenGain, 1), mx); g.blueGain = min(max(g.blueGain, 1), mx)
                dev.setWhiteBalanceModeLocked(with: g, completionHandler: nil)
            }
            if dev.isFocusModeSupported(.continuousAutoFocus) { dev.focusMode = .continuousAutoFocus }
            dev.unlockForConfiguration()
        }

        // Pinhole focal length from the format's horizontal FOV (along the sensor's long side, which is
        // the image HEIGHT once rotated to portrait: 1280 px for the 1280×720 preset).
        let fov = Double(dev.activeFormat.videoFieldOfView) * .pi / 180
        let focal = (1280.0 / 2) / tan(fov / 2)
        let mfd = dev.minimumFocusDistance > 0 ? Double(dev.minimumFocusDistance) : nil
        let af = dev.isFocusModeSupported(.autoFocus)
        let maxZ = Double(min(dev.activeFormat.videoMaxZoomFactor, 4))
        let d = "\(front ? "front" : "back") wide-angle camera\nAF=\(af) minFocus=\(mfd.map { "\(Int($0))mm" } ?? "?") torch=\(dev.hasTorch)\n" +
            "focalPx=\(Int(focal)) fov=\(String(format: "%.0f", Double(dev.activeFormat.videoFieldOfView)))° maxZoom=\(String(format: "%.1f", maxZ))"
        return Caps(hasTorch: dev.hasTorch, autoFocus: af, minFocusMM: mfd, maxZoom: maxZ, focalPx1x: focal, describe: d)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        onFrame?(pb, CACurrentMediaTime() * 1000)
    }

    // MARK: - Controls

    /// Image-normalised point (upright, un-mirrored) → AVCaptureDevice point of interest. Must run on main.
    @MainActor
    func devicePoint(nx: Double, ny: Double, imageSize: CGSize) -> CGPoint? {
        guard let layer = previewLayer, layer.bounds.width > 0 else { return nil }
        let p = ViewMapping.imageToView(nx: nx, ny: ny, imageSize: imageSize, viewSize: layer.bounds.size, mirrored: front)
        return layer.captureDevicePointConverted(fromLayerPoint: p)
    }

    /// While guiding: keep continuous AF but aimed at the eye (close-up AF otherwise picks lashes/brow).
    func setFocusPoint(_ p: CGPoint) {
        guard let d = device, d.isFocusPointOfInterestSupported, (try? d.lockForConfiguration()) != nil else { return }
        d.focusPointOfInterest = p
        if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus }
        d.unlockForConfiguration()
    }

    func setZoom(_ z: Double) {
        guard let d = device, (try? d.lockForConfiguration()) != nil else { return }
        d.videoZoomFactor = CGFloat(min(max(z, 1), Double(min(d.activeFormat.videoMaxZoomFactor, 4))))
        d.unlockForConfiguration()
    }

    /// Focus + meter on the eye, bias exposure down for headroom, wait to settle, then lock AE and AF.
    func focusAndLock(devicePoint p: CGPoint?, ev: Float) async {
        guard let d = device, (try? d.lockForConfiguration()) != nil else { return }
        if let p, d.isFocusPointOfInterestSupported, d.isFocusModeSupported(.autoFocus) {
            d.focusPointOfInterest = p; d.focusMode = .autoFocus
        }
        if let p, d.isExposurePointOfInterestSupported, d.isExposureModeSupported(.continuousAutoExposure) {
            d.exposurePointOfInterest = p; d.exposureMode = .continuousAutoExposure
        }
        let bias = min(max(ev, d.minExposureTargetBias), d.maxExposureTargetBias)
        d.setExposureTargetBias(bias, completionHandler: nil)
        d.unlockForConfiguration()
        appliedEV = bias
        try? await Task.sleep(nanoseconds: 450_000_000)
        let t0 = Date()
        while (d.isAdjustingFocus || d.isAdjustingExposure) && Date().timeIntervalSince(t0) < 1.2 {
            try? await Task.sleep(nanoseconds: 30_000_000)
        }
        guard (try? d.lockForConfiguration()) != nil else { return }
        if d.isExposureModeSupported(.locked) { d.exposureMode = .locked }
        if d.isFocusModeSupported(.locked) { d.focusMode = .locked }
        d.unlockForConfiguration()
    }

    func setTorch(_ on: Bool) {
        guard let d = device, d.hasTorch, (try? d.lockForConfiguration()) != nil else { return }
        if on { try? d.setTorchModeOn(level: Thresholds.torchLevel) } else { d.torchMode = .off }
        d.unlockForConfiguration()
    }

    func unlock() {
        guard let d = device, (try? d.lockForConfiguration()) != nil else { return }
        if d.hasTorch { d.torchMode = .off }
        if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure }
        d.setExposureTargetBias(0, completionHandler: nil)
        if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus }
        d.unlockForConfiguration()
    }

    /// Rough scene illuminance from the auto-exposure settings (iOS has no public ambient light sensor).
    /// EV100 = log2(N²/t) − log2(ISO/100), and E ≈ 2.5 · 2^EV100 lux (ISO 2720 incident-meter constant
    /// C = 250). Approximate: AE meters reflected light. Only meaningful before exposure is biased/locked.
    func estimatedLux() -> Double? {
        guard let d = device else { return nil }
        let t = d.exposureDuration.seconds, iso = Double(d.iso), n = Double(d.lensAperture)
        guard t > 0, iso > 0, n > 0 else { return nil }
        return 2.5 * pow(2, log2(n * n / t) - log2(iso / 100))
    }
}

/// Upright image coordinates ↔ preview coordinates for `.resizeAspectFill` (same maths as Android ViewMapping).
enum ViewMapping {
    static func imageToView(nx: Double, ny: Double, imageSize: CGSize, viewSize: CGSize, mirrored: Bool) -> CGPoint {
        let x = mirrored ? 1 - nx : nx
        let s = max(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        let ox = (viewSize.width - imageSize.width * s) / 2, oy = (viewSize.height - imageSize.height * s) / 2
        return CGPoint(x: x * imageSize.width * s + ox, y: ny * imageSize.height * s + oy)
    }
}
