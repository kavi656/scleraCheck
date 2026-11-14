import Foundation

/// Pure image: packed 0xAARRGGBB, row-major. Converted from the camera's BGRA buffer at the edge.
public struct RgbImage: Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [UInt32]

    public init(width: Int, height: Int, pixels: [UInt32]) {
        precondition(pixels.count == width * height)
        self.width = width; self.height = height; self.pixels = pixels
    }

    @inline(__always) public func r(_ i: Int) -> Int { Int((pixels[i] >> 16) & 0xFF) }
    @inline(__always) public func g(_ i: Int) -> Int { Int((pixels[i] >> 8) & 0xFF) }
    @inline(__always) public func b(_ i: Int) -> Int { Int(pixels[i] & 0xFF) }
}

public struct Pt: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
    public func dist(_ o: Pt) -> Double { hypot(x - o.x, y - o.y) }
}

/// One eye's landmarks in pixel coordinates of an upright, un-mirrored image.
public struct EyeGeometry: Sendable {
    /// Closed eyelid contour, ordered around the eye.
    public var contour: [Pt]
    public var irisCenter: Pt
    public var irisRadius: Double
    public var innerCorner: Pt
    public var outerCorner: Pt
    public var upperLid: Pt
    public var lowerLid: Pt

    public init(contour: [Pt], irisCenter: Pt, irisRadius: Double, innerCorner: Pt, outerCorner: Pt, upperLid: Pt, lowerLid: Pt) {
        self.contour = contour; self.irisCenter = irisCenter; self.irisRadius = irisRadius
        self.innerCorner = innerCorner; self.outerCorner = outerCorner; self.upperLid = upperLid; self.lowerLid = lowerLid
    }

    public var width: Double { innerCorner.dist(outerCorner) }
    /// Lid opening / eye width (eye aspect ratio, Soukupová & Čech 2016). Low ⇒ blink.
    public var openness: Double { width > 0 ? upperLid.dist(lowerLid) / width : 0 }
    /// Signed horizontal iris offset vs corner midpoint, fraction of half-width.
    public var gazeOffset: Double {
        let mx = (innerCorner.x + outerCorner.x) / 2, half = width / 2
        return half > 0 ? (irisCenter.x - mx) / half : 0
    }
    public var bounds: (Int, Int, Int, Int) {
        let xs = contour.map(\.x), ys = contour.map(\.y)
        return (Int(xs.min()!), Int(ys.min()!), Int(xs.max()!) + 1, Int(ys.max()!) + 1)
    }
    public func centroid() -> Pt {
        Pt(contour.map(\.x).reduce(0, +) / Double(contour.count), contour.map(\.y).reduce(0, +) / Double(contour.count))
    }
    public func shifted(dx: Double, dy: Double) -> EyeGeometry {
        func s(_ p: Pt) -> Pt { Pt(p.x + dx, p.y + dy) }
        return EyeGeometry(contour: contour.map(s), irisCenter: s(irisCenter), irisRadius: irisRadius,
                           innerCorner: s(innerCorner), outerCorner: s(outerCorner), upperLid: s(upperLid), lowerLid: s(lowerLid))
    }
}

public enum PolygonUtil {
    /// Even-odd ray-casting point-in-polygon.
    public static func contains(_ poly: [Pt], _ x: Double, _ y: Double) -> Bool {
        var inside = false
        var j = poly.count - 1
        for i in poly.indices {
            let pi = poly[i], pj = poly[j]
            if (pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x { inside.toggle() }
            j = i
        }
        return inside
    }

    public static func shrink(_ poly: [Pt], _ c: Pt, _ fx: Double, _ fy: Double) -> [Pt] {
        poly.map { Pt(c.x + ($0.x - c.x) * fx, c.y + ($0.y - c.y) * fy) }
    }

    /// Orders points by angle around their centroid (Vision returns some regions as unordered sets).
    public static func orderAround(_ pts: [Pt]) -> [Pt] {
        guard !pts.isEmpty else { return pts }
        let cx = pts.map(\.x).reduce(0, +) / Double(pts.count), cy = pts.map(\.y).reduce(0, +) / Double(pts.count)
        return pts.sorted { atan2($0.y - cy, $0.x - cx) < atan2($1.y - cy, $1.x - cx) }
    }
}

/// Sclera segmentation (port of `ScleraSegmenter.kt`). Landmark-polygon approach as in BiliScreen
/// (Mariakakis et al., IMWUT 2017), which also excluded vessels and glare.
public enum ScleraSegmenter {
    public struct Mask: Sendable {
        public let candidate: [Int]
        public let clean: [Int]
        public let glareCount: Int
        public let vesselCount: Int
        public let darkCount: Int
        public var glareFraction: Double { candidate.isEmpty ? 1 : Double(glareCount) / Double(candidate.count) }
        public var vesselFraction: Double { candidate.isEmpty ? 0 : Double(vesselCount) / Double(candidate.count) }
    }

    /// Inside the (shrunk) eyelid polygon and outside the dilated iris disk.
    public static func candidate(_ img: RgbImage, _ eye: EyeGeometry) -> [Int] {
        let c = eye.centroid()
        let poly = PolygonUtil.shrink(eye.contour, c, Thresholds.lidShrinkX, Thresholds.lidShrinkY)
        let r = eye.irisRadius * Thresholds.irisDilate, r2 = r * r
        let b = eye.bounds
        let x0 = min(max(b.0, 0), img.width - 1), x1 = min(max(b.2, 0), img.width)
        let y0 = min(max(b.1, 0), img.height - 1), y1 = min(max(b.3, 0), img.height)
        var out: [Int] = []
        guard x1 > x0, y1 > y0 else { return out }
        for y in y0..<y1 {
            for x in x0..<x1 {
                let dx = Double(x) - eye.irisCenter.x, dy = Double(y) - eye.irisCenter.y
                if dx * dx + dy * dy <= r2 { continue }
                if PolygonUtil.contains(poly, Double(x) + 0.5, Double(y) + 0.5) { out.append(y * img.width + x) }
            }
        }
        return out
    }

    /// Clean the candidate region using the LIT frame. Brightness reference is a high percentile of
    /// unsaturated pixels (the sclera is the brightest diffuse tissue) — field data showed a median
    /// reference keeps lashes and throws sclera away when lashes fill most of the polygon.
    public static func segment(_ img: RgbImage, _ eye: EyeGeometry) -> Mask {
        let cand = candidate(img, eye)
        if cand.isEmpty { return Mask(candidate: [], clean: [], glareCount: 0, vesselCount: 0, darkCount: 0) }
        let lut = ColourMath.linearLUT
        var lum = [Double](repeating: 0, count: cand.count)
        var red = [Double](repeating: 0, count: cand.count)
        var sat = [Bool](repeating: false, count: cand.count)
        let s8 = Thresholds.saturation8bit
        for (k, i) in cand.enumerated() {
            let r8 = img.r(i), g8 = img.g(i), b8 = img.b(i)
            let r = lut[r8], g = lut[g8], b = lut[b8]
            lum[k] = ColourMath.luminance(r, g, b)
            red[k] = r / (g + 1e-4)
            sat[k] = r8 >= s8 || g8 >= s8 || b8 >= s8
        }
        let unsat = lum.indices.filter { !sat[$0] }.map { lum[$0] }
        let ref = unsat.isEmpty ? Double.greatestFiniteMagnitude : ColourMath.percentile(unsat, Thresholds.scleraRefPercentile)
        var glare = 0, dark = 0
        var keep: [Int] = []
        for k in cand.indices {
            if sat[k] || lum[k] > ref * Thresholds.glareLumFactor { glare += 1 }
            else if lum[k] < ref * Thresholds.darkLumFactor { dark += 1 }
            else { keep.append(k) }
        }
        let medRed = ColourMath.median(keep.map { red[$0] })
        var clean: [Int] = []
        var vessel = 0
        for k in keep {
            if red[k] > medRed * Thresholds.vesselRednessFactor { vessel += 1 } else { clean.append(cand[k]) }
        }
        return Mask(candidate: cand, clean: clean, glareCount: glare, vesselCount: vessel, darkCount: dark)
    }
}

/// Focus measure: variance of the Laplacian (Pech-Pacheco et al., ICPR 2000), over the eye box.
public enum Sharpness {
    public static func varianceOfLaplacian(_ img: RgbImage, _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Double {
        let ax = min(max(x0, 1), img.width - 2), bx = min(max(x1, 2), img.width - 1)
        let ay = min(max(y0, 1), img.height - 2), by = min(max(y1, 2), img.height - 1)
        guard bx > ax, by > ay else { return 0 }
        @inline(__always) func grey(_ i: Int) -> Int {
            let p = img.pixels[i]
            return (Int((p >> 16) & 0xFF) * 77 + Int((p >> 8) & 0xFF) * 150 + Int(p & 0xFF) * 29) >> 8
        }
        let w = img.width
        var sum = 0.0, sum2 = 0.0, n = 0.0
        for y in ay..<by {
            for x in ax..<bx {
                let i = y * w + x
                let lap = Double(grey(i - 1) + grey(i + 1) + grey(i - w) + grey(i + w) - 4 * grey(i))
                sum += lap; sum2 += lap * lap; n += 1
            }
        }
        if n == 0 { return 0 }
        let mean = sum / n
        return sum2 / n - mean * mean
    }

    public static func ofEye(_ img: RgbImage, _ eye: EyeGeometry) -> Double {
        let b = eye.bounds
        return varianceOfLaplacian(img, b.0, b.1, b.2, b.3)
    }
}
