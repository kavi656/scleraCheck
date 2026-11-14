import Foundation

/// EVERY tunable number, each with where it came from (CLAUDE.md §5). Port of Android `Thresholds.kt`;
/// keep the two in sync. Values marked [PLACEHOLDER] must be replaced with numbers from the
/// lighting-invariance and repeatability experiments (§6). When unsure, err towards REFER (§1.2).
public enum Thresholds {
    // MARK: Capture / guidance

    /// Typical adult visible iris diameter 11.7 ± 0.5 mm (Caroline & Andre, Contact Lens Spectrum 2002).
    public static let irisDiameterMM = 11.7

    /// iOS only: Apple Vision gives eye contours and a pupil point but no iris ring, so the eye's
    /// corner-to-corner width is the size reference. Adult horizontal palpebral fissure ≈ 28–30 mm
    /// (e.g. Farkas, Anthropometry of the Head and Face, 1994). Used for distance and iris radius.
    public static let eyeWidthMM = 29.0

    /// Default target distance band; widened at runtime past the lens's minimum focus distance.
    public static let distMinMM = 100.0
    public static let distMaxMM = 150.0
    public static let focusMarginMM = 20.0

    /// Target band for this camera: default 100–150 mm, pushed beyond the closest focus distance.
    public static func distanceBand(minFocusMM: Double?) -> (Double, Double) {
        let lo = max(distMinMM, (minFocusMM ?? 0) + focusMarginMM)
        return (lo, max(distMaxMM, lo + 50))
    }

    /// Fixed-focus front cameras (pre-iPhone 14) focus at roughly arm's length; keep the eye far enough
    /// to be acceptably sharp and compensate with zoom. [PLACEHOLDER] check on a real device.
    public static let fixedFocusBandMM = (250.0, 320.0)

    public static let centreTolerance = 0.35
    public static let stableMS = 500.0
    public static let stillMaxMove = 0.25
    /// EAR-style openness threshold (Soukupová & Čech 2016 use ≈0.2).
    public static let minOpenness = 0.18
    public static let minGazeOffset = 0.20

    /// Zoom: 2× is a sensor crop on a 12 MP sensor, still above our analysis resolution.
    public static let zoomTarget = 2.0
    public static let zoomStartFactor = 1.5
    public static let zoomStep = 0.07
    public static let zoomLostMS = 1200.0

    public static let burstOpenFrames = 4
    public static let burstMaxFrames = 20
    public static let ambientBurstMS = 1500.0
    public static let torchSettleMS = 250.0
    public static let screenSettleMS = 450.0
    /// Hard safety cap on light-on time per burst (CLAUDE.md §1.3).
    public static let torchMaxMS = 2000.0
    /// Rear torch level (AVCaptureDevice.setTorchModeOn(level:), 0–1). [PLACEHOLDER]
    public static let torchLevel: Float = 0.6
    /// Exposure bias before locking, for headroom (field data: torch 3.5–17× ambient clipped channels).
    public static let torchExposureEV: Float = -2.0
    public static let screenExposureEV: Float = -1.0
    public static let maxSideAttempts = 3

    // MARK: Segmentation
    public static let lidShrinkX = 0.88
    public static let lidShrinkY = 0.72
    public static let irisDilate = 1.18
    public static let saturation8bit = 245
    /// Sclera reference = 95th percentile of unsaturated luminance (sclera wins even when <20% of polygon).
    public static let scleraRefPercentile = 0.95
    public static let glareLumFactor = 1.35
    public static let darkLumFactor = 0.5
    public static let vesselRednessFactor = 1.25

    // MARK: Measurement
    /// Torch-only signal is scaled to this median Y before Lab, removing distance/fall-off from b*.
    public static let normalisedY = 0.75

    // MARK: Quality gate (CLAUDE.md §4) — all [PLACEHOLDER]
    public static let minCleanPixels = 300
    public static let minSharpness = 25.0
    public static let minTorchRatio = 0.5
    public static let maxGlareFraction = 0.30
    public static let maxLRDisagreement = 6.0
    public static let maxVesselFraction = 0.30

    // MARK: Decision rule (CLAUDE.md §5)
    /// T_change [PLACEHOLDER]: target = mean + 3·SD of one person's check-to-check noise.
    public static let tChange = 3.0
    public static let consecutiveForRefer = 2
    /// T_absolute [PLACEHOLDER]: normal sclera b* ≈ 8–14 in published studies; 20 is clearly yellow.
    public static let tAbsolute = 20.0
}
