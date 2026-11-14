import SwiftUI
import AVFoundation
import ScleraCore

struct CheckView: View {
    let isBaseline: Bool
    let onExit: () -> Void
    @StateObject private var session: CheckSession
    @State private var permission = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var savedBrightness: CGFloat = UIScreen.main.brightness

    init(isBaseline: Bool, onExit: @escaping () -> Void) {
        self.isBaseline = isBaseline
        self.onExit = onExit
        _session = StateObject(wrappedValue: CheckSession(isBaseline: isBaseline))
    }

    var body: some View {
        Group {
            if permission == .authorized {
                if case .finished(let f) = session.phase {
                    ResultView(result: f, session: session, onExit: finish)
                } else {
                    capturePane
                }
            } else {
                VStack(spacing: 16) {
                    Text("ScleraCheck needs the camera to look at your eye. Nothing is uploaded.")
                    Button("Allow camera") {
                        AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { permission = ok ? .authorized : .denied } }
                    }.buttonStyle(.borderedProminent)
                    if permission == .denied { Text("Camera access is off. Turn it on in Settings → ScleraCheck.").font(.footnote) }
                    Button("Back", action: onExit)
                }.padding(24)
            }
        }
        .onAppear {
            if permission == .notDetermined {
                AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { permission = ok ? .authorized : .denied } }
            }
        }
        .onChange(of: permission) { p in if p == .authorized { session.startCamera() } }
        .task { if permission == .authorized { session.startCamera() } }
        .onDisappear { session.shutdown(); UIScreen.main.brightness = savedBrightness }
    }

    private func finish() { session.shutdown(); onExit() }

    private var capturePane: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                ZStack(alignment: .topLeading) {
                    CameraPreview(camera: session.camera)
                    if session.devMode {
                        EyeOverlay(live: session.live, mirrored: session.front)
                        Text(devText).font(.system(size: 11, design: .monospaced)).foregroundStyle(.yellow)
                            .padding(6).background(.black.opacity(0.55))
                    }
                }
                .aspectRatio(3.0 / 4.0, contentMode: .fit)
                .clipped()

                VStack(spacing: 12) {
                    phaseControls
                    Button("Cancel") { session.cancel(); finish() }.foregroundStyle(.white).buttonStyle(.bordered)
                }
                .padding(20)
                Spacer(minLength: 0)
            }
            // Front mode: the screen is the light. Black for the ambient burst, full-brightness white for the lit burst.
            switch session.screenLight {
            case .white: Color.white.ignoresSafeArea()
            case .black: Color.black.ignoresSafeArea()
            case .normal: EmptyView()
            }
        }
        .onChange(of: session.screenLight) { l in
            if l == .white { savedBrightness = UIScreen.main.brightness; UIScreen.main.brightness = 1 }
            else if l == .normal { UIScreen.main.brightness = savedBrightness }
        }
        .statusBarHidden(session.screenLight != .normal)
    }

    @ViewBuilder private var phaseControls: some View {
        switch session.phase {
        case .loading:
            ProgressView().tint(.white)
        case .intro(let advice):
            Text(session.front ? "Hold the phone like a selfie. The screen will go black, then flash white for about a second."
                               : "A light on the back of the phone will turn on for about a second during the check.")
                .foregroundStyle(.white).multilineTextAlignment(.center)
            if let advice { Text(advice).foregroundStyle(.orange).multilineTextAlignment(.center) }
            Button { session.start() } label: {
                Text(isBaseline ? "Start baseline capture" : "Start").font(.title3).frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.borderedProminent).disabled(session.caps == nil)
        case .guiding(let step, let text):
            Text(step == 0 ? "Side 1 of 2" : "Side 2 of 2").foregroundStyle(.gray)
            Text(text).font(.title.bold()).foregroundStyle(.white).multilineTextAlignment(.center)
        case .capturing:
            Text("Capturing… hold still").font(.title).foregroundStyle(.white)
        case .processing:
            ProgressView().tint(.white); Text("Measuring…").foregroundStyle(.white)
        case .finished:
            EmptyView()
        }
    }

    private var devText: String {
        let l = session.live
        var s = "dist: \(l.distanceMM.map { "\(Int($0)) mm" } ?? "—")"
        if let b = session.distBand { s += " (target \(Int(b.0))–\(Int(b.1)))" }
        s += "\nzoom: \(String(format: "%.2f", l.zoom))×  sharp: \(l.sharpness.map { "\(Int($0))" } ?? "—")"
        s += "\ngaze: \(l.eye.map { String(format: "%+.2f", $0.gazeOffset) } ?? "—")  open: \(l.eye.map { String(format: "%.2f", $0.openness) } ?? "—")"
        s += "\nlive b* (uncorrected): \(l.liveB.map { String(format: "%.1f", $0) } ?? "—")"
        if let c = session.caps { s += "\n" + c.describe }
        return s
    }
}

/// AVCaptureVideoPreviewLayer in SwiftUI (aspect-fill; the layer mirrors the front camera automatically).
struct CameraPreview: UIViewRepresentable {
    let camera: CameraController

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.session = camera.session
        v.previewLayer.videoGravity = .resizeAspectFill
        camera.previewLayer = v.previewLayer
        return v
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

/// Developer overlay: eyelid contour (cyan), segmentation polygon (green), excluded iris disk (magenta).
struct EyeOverlay: View {
    let live: LiveInfo
    let mirrored: Bool
    var body: some View {
        Canvas { ctx, size in
            guard let eye = live.eye, live.imageSize.width > 0 else { return }
            func pt(_ p: Pt) -> CGPoint {
                ViewMapping.imageToView(nx: p.x / live.imageSize.width, ny: p.y / live.imageSize.height,
                                        imageSize: live.imageSize, viewSize: size, mirrored: mirrored)
            }
            func poly(_ pts: [Pt], _ c: Color) {
                var path = Path()
                for (i, p) in pts.enumerated() {
                    if i == 0 { path.move(to: pt(p)) } else { path.addLine(to: pt(p)) }
                }
                path.closeSubpath()
                ctx.stroke(path, with: .color(c), lineWidth: 2)
            }
            poly(eye.contour, .cyan)
            poly(PolygonUtil.shrink(eye.contour, eye.centroid(), Thresholds.lidShrinkX, Thresholds.lidShrinkY), .green)
            let c = pt(eye.irisCenter)
            let s = max(size.width / live.imageSize.width, size.height / live.imageSize.height)
            let r = eye.irisRadius * Thresholds.irisDilate * s
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(.pink), lineWidth: 2)
        }
        .allowsHitTesting(false)
    }
}

struct ResultView: View {
    let result: CheckPhase.Finished
    let session: CheckSession
    let onExit: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                let d = result.decision
                if result.isBaseline, d.outcome != .retake, let b = d.bStar {
                    Text("Baseline captured").font(.largeTitle.bold())
                    Text(String(format: "Measured yellowness index b* = %.1f", b)).font(.title3)
                    Text("Clinician: confirm the patient's current status.")
                    Button { session.confirmBaseline(b, monitoring: false); onExit() } label: {
                        Text("Not currently jaundiced — save as baseline").frame(maxWidth: .infinity)
                    }.buttonStyle(.borderedProminent)
                    Button { session.confirmBaseline(b, monitoring: true); onExit() } label: {
                        Text("Currently jaundiced — save as monitoring baseline").frame(maxWidth: .infinity)
                    }.buttonStyle(.bordered)
                    Button("Discard", action: onExit).buttonStyle(.bordered)
                } else {
                    let c = content(d)
                    Text(c.title).font(.system(size: 32, weight: .bold)).foregroundStyle(.white)
                        .padding(24).frame(maxWidth: .infinity, alignment: .leading)
                        .background(c.colour, in: RoundedRectangle(cornerRadius: 14))
                    Text(c.steps).font(.title3)
                    if d.outcome != .retake { Text(d.explanation).font(.body) }
                    Button(action: onExit) { Text("Done").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                }
                if session.devMode { DevPatches(result: result) }
                NotDiagnosticNotice()
            }.padding(24)
        }
    }

    private func content(_ d: Decision) -> (title: String, colour: Color, steps: String) {
        switch d.outcome {
        case .noConcern:
            return ("No concern", okGreen, "No sign of increased yellowing compared with your baseline. Do your next check as planned. If you notice yellow skin or eyes, dark urine, pale stools or itching, contact your GP anyway.")
        case .refer:
            return ("Refer to GP", alertRed, "Signs of yellowing compared with your baseline. Please contact your GP surgery today. This is not a diagnosis. If you feel very unwell, call NHS 111.")
        case .retake:
            return ("Retake needed", amber, d.retakeReasons.map { "• " + $0.message }.joined(separator: "\n"))
        }
    }
}

/// Developer mode: the exact sclera pixels measured per side, so capture quality can be judged by eye.
struct DevPatches: View {
    let result: CheckPhase.Finished
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("What was measured (white of the eye only; black = excluded)").font(.subheadline.bold())
            HStack(alignment: .top, spacing: 8) {
                ForEach(0..<2, id: \.self) { side in
                    VStack {
                        if let img = result.patches.count > side ? result.patches[side] : nil {
                            Image(uiImage: img).resizable().interpolation(.none).scaledToFit().background(.black)
                        } else {
                            Text("no usable frame").font(.caption).foregroundStyle(.white)
                                .frame(maxWidth: .infinity, minHeight: 70).background(.gray)
                        }
                        let m = side == 0 ? result.first : result.second
                        Text("Side \(side + 1): try \(result.attempts.count > side ? result.attempts[side] : 0), \(m?.cleanPixels ?? 0) px" +
                             (m.map { ", \($0.torchOpenFrames)/\($0.torchFrames) open" } ?? "")).font(.caption)
                    }.frame(maxWidth: .infinity)
                }
            }
            Text(devNumbers).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
        }
    }
    private var devNumbers: String {
        func s(_ m: SideMeasurement?) -> String {
            guard let m else { return "—" }
            return String(format: "b*=%.2f a*=%.2f naive=%@ glare=%.2f vessel=%.2f light/amb=%.2f sharp=%.0f gaze=%+.2f",
                          m.bStar, m.aStar, m.naiveBStar.map { String(format: "%.2f", $0) } ?? "—",
                          m.glareFraction, m.vesselFraction, m.torchToAmbient, m.sharpness, m.gazeOffset)
        }
        return "Side 1: \(s(result.first))\nSide 2: \(s(result.second))\nΔ=\(result.decision.delta.map { String(format: "%.2f", $0) } ?? "—")"
    }
}
