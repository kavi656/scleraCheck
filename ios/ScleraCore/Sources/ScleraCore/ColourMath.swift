import Foundation

/// Pure colour maths (port of Android `colour/ColourMath.kt`).
///
/// Conventions: linear RGB in [0,1], sRGB primaries, D65 white.
/// References:
///  - IEC 61966-2-1:1999 (sRGB transfer function and RGB→XYZ matrix).
///  - CIE 15:2004 Colorimetry, CIELAB definition, with the CIE 1976 piecewise f(t).
public enum ColourMath {
    /// D65 reference white, Y normalised to 1 (CIE 15:2004, 2° observer).
    public static let xn = 0.95047, yn = 1.0, zn = 1.08883

    /// sRGB EOTF: encoded value in [0,1] → linear light. We assume the camera output follows the sRGB
    /// tone curve. That's an approximation (Apple applies its own tone map), but it's the same on every
    /// check on the same device, and decisions are relative to the patient's own baseline on that device.
    public static func srgbToLinear(_ v: Double) -> Double {
        v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
    }

    public static func linearToSrgb(_ v: Double) -> Double {
        v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1.0 / 2.4) - 0.055
    }

    /// 8-bit → linear lookup table for hot pixel loops.
    public static let linearLUT: [Double] = (0..<256).map { srgbToLinear(Double($0) / 255.0) }

    /// Linear sRGB → CIE XYZ (D65), IEC 61966-2-1 matrix.
    public static func linearRgbToXyz(_ r: Double, _ g: Double, _ b: Double) -> (Double, Double, Double) {
        (0.4124564 * r + 0.3575761 * g + 0.1804375 * b,
         0.2126729 * r + 0.7151522 * g + 0.0721750 * b,
         0.0193339 * r + 0.1191920 * g + 0.9503041 * b)
    }

    /// Relative luminance Y of linear sRGB.
    @inline(__always)
    public static func luminance(_ r: Double, _ g: Double, _ b: Double) -> Double {
        0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    }

    private static let eps = 216.0 / 24389.0
    private static let kappa = 24389.0 / 27.0

    @inline(__always)
    private static func f(_ t: Double) -> Double { t > eps ? cbrt(t) : (kappa * t + 16.0) / 116.0 }

    /// CIE XYZ → CIELAB (D65). Returns (L*, a*, b*).
    public static func xyzToLab(_ x: Double, _ y: Double, _ z: Double) -> (l: Double, a: Double, b: Double) {
        let fx = f(x / xn), fy = f(y / yn), fz = f(z / zn)
        return (116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    public static func linearRgbToLab(_ r: Double, _ g: Double, _ b: Double) -> (l: Double, a: Double, b: Double) {
        let (x, y, z) = linearRgbToXyz(r, g, b)
        return xyzToLab(x, y, z)
    }

    public static func srgb8ToLab(_ r: Int, _ g: Int, _ b: Int) -> (l: Double, a: Double, b: Double) {
        linearRgbToLab(linearLUT[r], linearLUT[g], linearLUT[b])
    }

    /// Flash/no-flash differencing per channel in LINEAR light (Petschnigg et al., SIGGRAPH 2004).
    /// Light adds linearly, so lit − ambient leaves only the controlled light's contribution.
    /// Exposure and white balance must be LOCKED between the two bursts.
    public static func torchOnly(_ lit: [Double], _ ambient: [Double]) -> [Double] {
        (0..<3).map { max(0.0, lit[$0] - ambient[$0]) }
    }

    public static func median(_ values: [Double]) -> Double {
        if values.isEmpty { return .nan }
        let s = values.sorted()
        let n = s.count
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    public static func percentile(_ values: [Double], _ p: Double) -> Double {
        if values.isEmpty { return .nan }
        let s = values.sorted()
        let i = min(max(Int(Double(s.count - 1) * p), 0), s.count - 1)
        return s[i]
    }
}

/// 3×3 device colour-correction matrix on linear RGB (port of `ColourCorrection.kt`).
/// Fitted by least squares from a colour reference card (Finlayson et al., IEEE TIP 2015 —
/// linear 3×3 baseline). Identity until the GP calibrates.
public struct ColourCorrection: Codable, Equatable, Sendable {
    public var m: [Double]

    public init(_ m: [Double] = ColourCorrection.identity) {
        precondition(m.count == 9)
        self.m = m
    }

    public static let identity: [Double] = [1, 0, 0, 0, 1, 0, 0, 0, 1]

    public func apply(_ v: [Double]) -> [Double] {
        [m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
         m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
         m[6] * v[0] + m[7] * v[1] + m[8] * v[2]]
    }

    /// Least squares: minimise Σ‖M·measuredᵢ − referenceᵢ‖² via the normal equations, per output row.
    public static func fit(measured: [[Double]], reference: [[Double]]) -> ColourCorrection? {
        guard measured.count == reference.count, measured.count >= 3 else { return nil }
        var ata = [[Double]](repeating: [0, 0, 0], count: 3)
        for p in measured { for i in 0..<3 { for j in 0..<3 { ata[i][j] += p[i] * p[j] } } }
        guard let inv = invert3(ata) else { return nil }
        var out = [Double](repeating: 0, count: 9)
        for row in 0..<3 {
            var atb = [0.0, 0.0, 0.0]
            for k in measured.indices { for i in 0..<3 { atb[i] += measured[k][i] * reference[k][row] } }
            for j in 0..<3 { out[row * 3 + j] = inv[j][0] * atb[0] + inv[j][1] * atb[1] + inv[j][2] * atb[2] }
        }
        return ColourCorrection(out)
    }

    static func invert3(_ a: [[Double]]) -> [[Double]]? {
        let det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
            - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
            + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        if abs(det) < 1e-12 { return nil }
        let d = 1.0 / det
        return [
            [(a[1][1] * a[2][2] - a[1][2] * a[2][1]) * d, (a[0][2] * a[2][1] - a[0][1] * a[2][2]) * d, (a[0][1] * a[1][2] - a[0][2] * a[1][1]) * d],
            [(a[1][2] * a[2][0] - a[1][0] * a[2][2]) * d, (a[0][0] * a[2][2] - a[0][2] * a[2][0]) * d, (a[0][2] * a[1][0] - a[0][0] * a[1][2]) * d],
            [(a[1][0] * a[2][1] - a[1][1] * a[2][0]) * d, (a[0][1] * a[2][0] - a[0][0] * a[2][1]) * d, (a[0][0] * a[1][1] - a[0][1] * a[1][0]) * d],
        ]
    }
}
