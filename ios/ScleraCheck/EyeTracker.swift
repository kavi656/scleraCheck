import Vision
import CoreVideo
import ScleraCore

/// Eye landmarks from Apple Vision (`VNDetectFaceLandmarksRequest`), on-device, no extra library.
///
/// Difference from Android (MediaPipe): Vision gives eyelid contours and a pupil point but no iris
/// ring, so the iris radius is estimated from the eye's corner-to-corner width:
///   r_iris ≈ width × (11.7 mm / 29 mm) / 2   (iris diameter / palpebral fissure length; see Thresholds).
/// The dilated iris disk (×1.18) absorbs the person-to-person variation.
final class EyeTracker {
    private let request: VNDetectFaceLandmarksRequest = {
        let r = VNDetectFaceLandmarksRequest()
        r.revision = VNDetectFaceLandmarksRequestRevision3
        return r
    }()

    /// Eyes in pixel coordinates of the upright frame (origin top-left), or nil if no face.
    func detect(_ pb: CVPixelBuffer) -> [EyeGeometry]? {
        let handler = VNImageRequestHandler(cvPixelBuffer: pb, orientation: .up, options: [:])
        do { try handler.perform([request]) } catch { return nil }
        guard let face = request.results?.first, let lm = face.landmarks else { return nil }
        let W = Double(CVPixelBufferGetWidth(pb)), H = Double(CVPixelBufferGetHeight(pb))
        let size = CGSize(width: W, height: H)
        // Vision image points have a bottom-left origin; flip to top-left.
        func pts(_ r: VNFaceLandmarkRegion2D?) -> [Pt]? {
            r?.pointsInImage(imageSize: size).map { Pt(Double($0.x), H - Double($0.y)) }
        }
        let faceMidX = Double(face.boundingBox.midX) * W

        func eye(_ region: VNFaceLandmarkRegion2D?, _ pupil: VNFaceLandmarkRegion2D?) -> EyeGeometry? {
            guard let raw = pts(region), raw.count >= 6 else { return nil }
            let contour = PolygonUtil.orderAround(raw)
            guard let left = contour.min(by: { $0.x < $1.x }), let right = contour.max(by: { $0.x < $1.x }) else { return nil }
            // Inner corner = the one nearer the face midline (nose).
            let leftIsInner = abs(left.x - faceMidX) < abs(right.x - faceMidX)
            let inner = leftIsInner ? left : right, outer = leftIsInner ? right : left
            let width = left.dist(right)
            guard width > 4 else { return nil }
            let midX = (left.x + right.x) / 2
            let middle = contour.filter { abs($0.x - midX) < width * 0.25 }
            let src = middle.isEmpty ? contour : middle
            let upper = src.min(by: { $0.y < $1.y })!, lower = src.max(by: { $0.y < $1.y })!
            let pupilPt = pts(pupil)?.first ?? Pt(midX, (upper.y + lower.y) / 2)
            let irisR = width * (Thresholds.irisDiameterMM / Thresholds.eyeWidthMM) / 2
            return EyeGeometry(contour: contour, irisCenter: pupilPt, irisRadius: irisR,
                               innerCorner: inner, outerCorner: outer, upperLid: upper, lowerLid: lower)
        }
        let eyes = [eye(lm.rightEye, lm.rightPupil), eye(lm.leftEye, lm.leftPupil)].compactMap { $0 }
        return eyes.isEmpty ? nil : eyes
    }
}

/// BGRA pixel buffer → pure RgbImage crop (0xAARRGGBB), so the core never sees the full face.
enum PixelBufferUtil {
    static func crop(_ pb: CVPixelBuffer, x0: Int, y0: Int, w: Int, h: Int) -> RgbImage {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        var px = [UInt32](repeating: 0, count: w * h)
        guard let base = CVPixelBufferGetBaseAddress(pb)?.assumingMemoryBound(to: UInt8.self) else {
            return RgbImage(width: w, height: h, pixels: px)
        }
        let bpr = CVPixelBufferGetBytesPerRow(pb)
        for y in 0..<h {
            let row = base + (y0 + y) * bpr
            for x in 0..<w {
                let p = row + (x0 + x) * 4 // B G R A
                px[y * w + x] = 0xFF00_0000 | UInt32(p[2]) << 16 | UInt32(p[1]) << 8 | UInt32(p[0])
            }
        }
        return RgbImage(width: w, height: h, pixels: px)
    }
}
